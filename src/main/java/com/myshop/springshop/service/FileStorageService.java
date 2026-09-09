package com.myshop.springshop.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.UUID;

@Service
public class FileStorageService {

    // Long side of a stored product photo, in pixels. Phone cameras commonly shoot
    // 3000px+ images; the shop only ever displays these in a grid or a single
    // product view, so anything past this is wasted bytes on every page load.
    private static final int MAX_DIMENSION = 1600;
    private static final float JPEG_QUALITY = 0.82f;

    private final Path uploadPath;

    public FileStorageService(@Value("${app.upload-dir:uploads}") String uploadDir) {
        this.uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(uploadPath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create upload directory", e);
        }
    }

    public Path getUploadPath() {
        return uploadPath;
    }

    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return "";
        }

        String originalName = StringUtils.cleanPath(file.getOriginalFilename() == null ? "" : file.getOriginalFilename());
        String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String storedName = UUID.randomUUID() + "_" + safeName;

        try {
            byte[] originalBytes = file.getBytes();
            byte[] bytesToStore = optimizeJpegIfPossible(originalBytes, file.getContentType());
            Files.write(uploadPath.resolve(storedName), bytesToStore,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return storedName;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store uploaded file", e);
        }
    }

    public void delete(String storedFileName) {
        if (!StringUtils.hasText(storedFileName)) {
            return;
        }

        Path target = uploadPath.resolve(storedFileName).normalize();
        if (!target.startsWith(uploadPath)) {
            return;
        }

        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete uploaded file", e);
        }
    }

    /**
     * Downscales large JPEG photos (e.g. straight off a phone camera) so product
     * pages load faster, correcting the pixel orientation using the JPEG's EXIF tag
     * before it's baked out (a re-encoded JPEG carries no EXIF, so this has to
     * happen now or the photo could come out sideways).
     * <p>
     * Only handles plain JPEG uploads and only the common camera orientations
     * (normal / 180° / 90° left / 90° right). Anything else - a non-JPEG image, a
     * mirrored orientation, a file ImageIO can't decode, any error at all - falls
     * back to the untouched original bytes. This feature must never be able to
     * break an upload or corrupt a photo.
     */
    private byte[] optimizeJpegIfPossible(byte[] originalBytes, String contentType) {
        boolean isJpeg = contentType != null
                && (contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/jpg"));
        if (!isJpeg) {
            return originalBytes;
        }

        try {
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(originalBytes));
            if (decoded == null) {
                return originalBytes;
            }

            int orientation = readJpegOrientation(originalBytes);
            BufferedImage oriented = orientation == 1 ? decoded : applyOrientation(decoded, orientation);
            if (oriented == null) {
                // Unsupported/mirrored orientation value - leave the file untouched
                // rather than risk producing a sideways or mirrored image.
                return originalBytes;
            }

            boolean withinBounds = oriented.getWidth() <= MAX_DIMENSION && oriented.getHeight() <= MAX_DIMENSION;
            if (withinBounds && orientation == 1) {
                // Already the right way up and small enough - nothing to fix.
                return originalBytes;
            }

            BufferedImage resized = withinBounds ? oriented : scaleDown(oriented, MAX_DIMENSION);
            byte[] reEncoded = encodeJpeg(resized, JPEG_QUALITY);
            return reEncoded != null ? reEncoded : originalBytes;
        } catch (Exception e) {
            return originalBytes;
        }
    }

    private BufferedImage scaleDown(BufferedImage image, int maxDimension) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = Math.min((double) maxDimension / width, (double) maxDimension / height);
        int newWidth = Math.max(1, (int) Math.round(width * scale));
        int newHeight = Math.max(1, (int) Math.round(height * scale));

        BufferedImage scaled = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(image, 0, 0, newWidth, newHeight, null);
        g.dispose();
        return scaled;
    }

    /**
     * Rotates a decoded image to match its EXIF orientation tag. Only the four
     * orientations real cameras actually produce are handled (1 = normal, which the
     * caller already skips; 3 = 180°; 6 = 90° CW; 8 = 90° CCW). Any other value
     * (the rare mirrored variants) returns null so the caller leaves the file alone.
     */
    private BufferedImage applyOrientation(BufferedImage image, int orientation) {
        int width = image.getWidth();
        int height = image.getHeight();
        AffineTransform transform = new AffineTransform();
        int newWidth = width;
        int newHeight = height;

        switch (orientation) {
            case 3:
                transform.translate(width, height);
                transform.rotate(Math.PI);
                break;
            case 6:
                transform.translate(height, 0);
                transform.rotate(Math.PI / 2);
                newWidth = height;
                newHeight = width;
                break;
            case 8:
                transform.translate(0, width);
                transform.rotate(-Math.PI / 2);
                newWidth = height;
                newHeight = width;
                break;
            default:
                return null;
        }

        BufferedImage rotated = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rotated.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, newWidth, newHeight);
        g.setTransform(transform);
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return rotated;
    }

    private byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            }
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Reads the EXIF "Orientation" tag (1-8) straight out of the JPEG bytes; 1 (normal) if absent or unreadable. */
    private int readJpegOrientation(byte[] jpegBytes) {
        try {
            if (jpegBytes.length < 4 || (jpegBytes[0] & 0xFF) != 0xFF || (jpegBytes[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int offset = 2;
            while (offset < jpegBytes.length - 3) {
                if ((jpegBytes[offset] & 0xFF) != 0xFF) {
                    break;
                }
                int marker = jpegBytes[offset + 1] & 0xFF;
                if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                    offset += 2;
                    continue;
                }
                if (marker == 0xD9 || marker == 0xDA) {
                    break; // end of image / start of scan - no more metadata ahead
                }
                if (offset + 4 > jpegBytes.length) {
                    break;
                }
                int segmentLength = ((jpegBytes[offset + 2] & 0xFF) << 8) | (jpegBytes[offset + 3] & 0xFF);
                if (marker == 0xE1) { // APP1 - typically EXIF
                    int exifStart = offset + 4;
                    byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.US_ASCII);
                    if (matches(jpegBytes, exifStart, exifHeader)) {
                        int orientation = parseExifOrientation(jpegBytes, exifStart + exifHeader.length);
                        if (orientation >= 1 && orientation <= 8) {
                            return orientation;
                        }
                    }
                }
                offset += 2 + segmentLength;
            }
        } catch (Exception e) {
            // Malformed/unexpected structure - treat as "no orientation info".
        }
        return 1;
    }

    private int parseExifOrientation(byte[] data, int tiffStart) {
        if (tiffStart + 8 > data.length) {
            return 1;
        }
        boolean littleEndian;
        if (data[tiffStart] == 'I' && data[tiffStart + 1] == 'I') {
            littleEndian = true;
        } else if (data[tiffStart] == 'M' && data[tiffStart + 1] == 'M') {
            littleEndian = false;
        } else {
            return 1;
        }

        int ifdOffset = readInt32(data, tiffStart + 4, littleEndian);
        int ifdStart = tiffStart + ifdOffset;
        if (ifdStart < 0 || ifdStart + 2 > data.length) {
            return 1;
        }
        int numEntries = readInt16(data, ifdStart, littleEndian);
        for (int i = 0; i < numEntries; i++) {
            int entryOffset = ifdStart + 2 + (i * 12);
            if (entryOffset + 12 > data.length) {
                break;
            }
            int tag = readInt16(data, entryOffset, littleEndian);
            if (tag == 0x0112) { // Orientation
                return readInt16(data, entryOffset + 8, littleEndian);
            }
        }
        return 1;
    }

    private boolean matches(byte[] data, int offset, byte[] expected) {
        if (offset < 0 || offset + expected.length > data.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private int readInt16(byte[] data, int offset, boolean littleEndian) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        return littleEndian ? (b1 << 8 | b0) : (b0 << 8 | b1);
    }

    private int readInt32(byte[] data, int offset, boolean littleEndian) {
        int b0 = data[offset] & 0xFF;
        int b1 = data[offset + 1] & 0xFF;
        int b2 = data[offset + 2] & 0xFF;
        int b3 = data[offset + 3] & 0xFF;
        return littleEndian
                ? (b3 << 24 | b2 << 16 | b1 << 8 | b0)
                : (b0 << 24 | b1 << 16 | b2 << 8 | b3);
    }
}
