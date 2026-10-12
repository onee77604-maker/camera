package com.ridiitols.gyrolock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes a JPEG-based Motion Photo while preserving normal JPEG marker ordering. */
final class MotionPhotoWriter {
    private static final byte[] XMP_HEADER = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.US_ASCII);
    private static final String XPACKET_BEGIN = "<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>";
    private static final String XPACKET_END = "<?xpacket end='w'?>";

    private MotionPhotoWriter() {}

    static void write(File jpeg, File mp4, File output, long presentationTimestampUs) throws IOException {
        if (!jpeg.isFile() || jpeg.length() < 4) throw new IOException("Foto JPEG tidak valid");
        if (!mp4.isFile() || mp4.length() < 1024) throw new IOException("Video Motion Photo tidak valid");
        long videoLength = mp4.length();
        byte[] xmpXml = (XPACKET_BEGIN + buildXmp(videoLength, presentationTimestampUs) + XPACKET_END).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payloadStream = new ByteArrayOutputStream(XMP_HEADER.length + xmpXml.length);
        payloadStream.write(XMP_HEADER);
        payloadStream.write(xmpXml);
        byte[] xmpPayload = payloadStream.toByteArray();
        int segmentLength = xmpPayload.length + 2;
        if (segmentLength > 65535) throw new IOException("Metadata XMP terlalu besar");

        File parent = output.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("Tidak dapat membuat folder output");
        try (FileInputStream in = new FileInputStream(jpeg); FileOutputStream out = new FileOutputStream(output)) {
            int a = in.read(), b = in.read();
            if (a != 0xFF || b != 0xD8) throw new IOException("Header JPEG tidak valid");
            out.write(a); out.write(b);

            // Keep JFIF APP0 immediately after SOI when present. Some image importers
            // are less tolerant of an XMP APP1 placed before the normal JPEG headers.
            int markerA = in.read();
            int markerB = in.read();
            if (markerA < 0 || markerB < 0) throw new IOException("JPEG terpotong");
            out.write(markerA); out.write(markerB);
            boolean inserted = false;
            if (markerA == 0xFF && markerB == 0xE0) {
                int hi = in.read(), lo = in.read();
                if (hi < 0 || lo < 0) throw new IOException("Segmen APP0 JPEG tidak lengkap");
                int len = (hi << 8) | lo;
                if (len < 2) throw new IOException("Panjang segmen APP0 tidak valid");
                out.write(hi); out.write(lo);
                copyExactly(in, out, len - 2);
                writeXmpSegment(out, xmpPayload, segmentLength);
                inserted = true;
            } else {
                // If there is no JFIF APP0, put XMP after SOI but preserve the first marker.
                writeXmpSegment(out, xmpPayload, segmentLength);
                out.write(markerA); out.write(markerB);
            }

            // Copy the remaining JPEG bytes unchanged. If APP0 was not first, the XMP
            // segment has already been inserted before the original first marker.
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            try (FileInputStream videoIn = new FileInputStream(mp4)) {
                while ((n = videoIn.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private static void writeXmpSegment(FileOutputStream out, byte[] payload, int segmentLength) throws IOException {
        out.write(0xFF); out.write(0xE1);
        out.write((segmentLength >> 8) & 0xFF); out.write(segmentLength & 0xFF);
        out.write(payload);
    }

    private static void copyExactly(FileInputStream in, FileOutputStream out, int count) throws IOException {
        byte[] buffer = new byte[8192];
        int remaining = count;
        while (remaining > 0) {
            int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (n < 0) throw new IOException("Segmen JPEG tidak lengkap");
            out.write(buffer, 0, n);
            remaining -= n;
        }
    }

    private static String buildXmp(long videoLength, long presentationTimestampUs) {
        long marker = Math.max(0L, presentationTimestampUs);
        return "<x:xmpmeta xmlns:x='adobe:ns:meta/'>" +
                "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>" +
                "<rdf:Description rdf:about='' " +
                "xmlns:Camera='http://ns.google.com/photos/1.0/camera/' " +
                "xmlns:GCamera='http://ns.google.com/photos/1.0/camera/' " +
                "xmlns:GContainer='http://ns.google.com/photos/1.0/container/' " +
                "xmlns:Item='http://ns.google.com/photos/1.0/container/item/' " +
                "Camera:MotionPhoto='1' Camera:MotionPhotoVersion='1' " +
                "Camera:MotionPhotoPresentationTimestampUs='" + marker + "' " +
                "GCamera:MotionPhoto='1' GCamera:MotionPhotoVersion='1' " +
                "GCamera:MicroVideo='1' GCamera:MicroVideoVersion='1' " +
                "GCamera:MicroVideoOffset='" + videoLength + "' " +
                "GCamera:MicroVideoPresentationTimestampUs='" + marker + "'>" +
                "<GContainer:Directory><rdf:Seq>" +
                "<rdf:li rdf:parseType='Resource'><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic><Item:Length>0</Item:Length><Item:Padding>0</Item:Padding></rdf:li>" +
                "<rdf:li rdf:parseType='Resource'><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>" + videoLength + "</Item:Length></rdf:li>" +
                "</rdf:Seq></GContainer:Directory>" +
                "</rdf:Description></rdf:RDF></x:xmpmeta>";
    }
}
