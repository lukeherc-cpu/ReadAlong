package com.example.readalong.data;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Test APK only. Its standalone process uses platform/Java classes, without target APK dependencies. */
public class OriginalBooksProvider extends DocumentsProvider {
    private boolean showNewBook = false;
    private boolean showEpub = false;
    private boolean showTranscript = false;
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("show-transcript".equals(method)) { showTranscript = "true".equals(arg); return new Bundle(); }
        if ("show-epub".equals(method)) { showEpub = "true".equals(arg); return new Bundle(); }
        if ("show-new-book".equals(method)) { showNewBook = "true".equals(arg); return new Bundle(); }
        if ("grant-visual".equals(method)) {
            long identity = Binder.clearCallingIdentity();
            try { getContext().grantUriPermission("com.example.readalong",
                DocumentsContract.buildDocumentUri("com.example.readalong.test.original-books", arg),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); }
            finally { Binder.restoreCallingIdentity(identity); }
            return new Bundle();
        }
        if (!"grant-original-book".equals(method)) return super.call(method, arg, extras);
        long identity = Binder.clearCallingIdentity();
        try {
            getContext().grantUriPermission("com.example.readalong",
                DocumentsContract.buildTreeDocumentUri("com.example.readalong.test.original-books", "book"),
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            getContext().grantUriPermission("com.example.readalong", DocumentsContract.buildTreeDocumentUri("com.example.readalong.test.original-books", "library"), Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            getContext().grantUriPermission("com.example.readalong", DocumentsContract.buildDocumentUri("com.example.readalong.test.original-books", "book/Disc 1/Part 2.wav"), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } finally { Binder.restoreCallingIdentity(identity); }
        return new Bundle();
    }
    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES});
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            if (column.equals(Root.COLUMN_ROOT_ID) || column.equals(Root.COLUMN_DOCUMENT_ID)) value = "book";
            else if (column.equals(Root.COLUMN_TITLE)) value = "Original test book";
            else if (column.equals(Root.COLUMN_FLAGS)) value = 0;
            else if (column.equals(Root.COLUMN_MIME_TYPES)) value = "audio/*";
            row.add(column, value);
        }
        return cursor;
    }
    private boolean directory(String id) { return id.equals("library") || id.equals("library/New book") || id.equals("book") || id.equals("book/Disc 1") || id.equals("book/Disc 2"); }
    private Cursor documents(String[] ids, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE});
        for (String id : ids) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String column : cursor.getColumnNames()) {
                Object value = null;
                if (column.equals(Document.COLUMN_DOCUMENT_ID)) value = id;
                else if (column.equals(Document.COLUMN_DISPLAY_NAME)) value = id.substring(id.lastIndexOf('/') + 1);
                else if (column.equals(Document.COLUMN_MIME_TYPE)) value = directory(id) ? Document.MIME_TYPE_DIR : id.endsWith(".epub") ? "application/epub+zip" : id.endsWith(".readalong") ? "application/zip" : "audio/wav";
                else if (column.equals(Document.COLUMN_FLAGS)) value = 0;
                else if (column.equals(Document.COLUMN_SIZE)) value = directory(id) ? 0 : 64044;
                row.add(column, value);
            }
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] projection) { return documents(new String[]{id}, projection); }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) {
        String[] ids = new String[0];
        if (id.equals("library")) ids = showNewBook ? new String[]{"book", "library/New book"} : new String[]{"book"};
        if (id.equals("library/New book")) ids = new String[]{"library/New book/New.wav"};
        if (id.equals("book")) ids = showEpub ? new String[]{"book/Disc 2", "book/Disc 1", "book/Companion.epub"} : new String[]{"book/Disc 2", "book/Disc 1"};
        if (id.equals("book/Disc 1")) ids = new String[]{"book/Disc 1/Part 10.wav", "book/Disc 1/Part 2.wav"};
        if (id.equals("book/Disc 2")) ids = new String[]{"book/Disc 2/Part 2.wav", "book/Disc 2/Part 1.wav"};
        if (id.equals("book") && showTranscript) {
            String[] next = java.util.Arrays.copyOf(ids, ids.length + 1); next[ids.length] = "book/Companion.readalong"; ids = next;
        }
        return documents(ids, projection);
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (mode.equals("r") && id.equals("book/Companion.readalong") && showTranscript) {
            File pack = new File(getContext().getCacheDir(), "companion.readalong");
            String[] sourceIds = {"book/Disc 2/Part 2.wav", "book/Disc 1/Part 10.wav", "book/Disc 2/Part 1.wav", "book/Disc 1/Part 2.wav"};
            try {
                org.json.JSONArray sources = new org.json.JSONArray(); StringBuilder rows = new StringBuilder();
                for (int i=0; i<sourceIds.length; i++) {
                    byte[] audio;
                    try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(openDocument(sourceIds[i], "r", signal))) {
                        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                        while ((n=input.read(buffer))!=-1) bytes.write(buffer,0,n); audio = bytes.toByteArray();
                    }
                    StringBuilder sha = new StringBuilder(); for (byte b:java.security.MessageDigest.getInstance("SHA-256").digest(audio)) sha.append(String.format(java.util.Locale.ROOT,"%02x", b & 255));
                    sources.put(new org.json.JSONObject().put("name",sourceIds[i].substring(sourceIds[i].lastIndexOf('/')+1)).put("size",audio.length).put("sha256",sha.toString()).put("durationMs",2000));
                    rows.append(new org.json.JSONObject().put("file",i).put("startMs",0).put("endMs",2000).put("result",new org.json.JSONObject().put("text","This original companion text is available before any transcription.").put("words",new org.json.JSONArray().put(new org.json.JSONObject().put("word","This original companion text is available before any transcription.").put("start",0).put("end",1.8))))).append('\n');
                }
                org.json.JSONObject manifest = new org.json.JSONObject().put("format","readalong-transcript").put("version",1).put("model","cactus-whistle-v2-b6e02f04").put("sources",sources).put("chunks",4).put("complete",true);
                try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(pack))) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("manifest.json")); zip.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                    zip.putNextEntry(new java.util.zip.ZipEntry("chunks.jsonl")); zip.write(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
                }
            } catch (Exception e) { throw new FileNotFoundException(e.toString()); }
            return ParcelFileDescriptor.open(pack, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (mode.equals("r") && id.equals("book/Companion.epub") && showEpub) {
            File epub = new File(getContext().getCacheDir(), "companion.epub");
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(new FileOutputStream(epub))) {
                String[] paths = {"mimetype", "META-INF/container.xml", "package.opf", "one.xhtml"};
                String[] text = {"application/epub+zip", "<container><rootfile full-path='package.opf'/></container>",
                    "<package><metadata><title>Companion book</title></metadata><manifest><item id='one' href='one.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/></spine></package>", "<html><body><p>This original companion text is available before any transcription.</p></body></html>"};
                for (int i=0; i<paths.length; i++) { zip.putNextEntry(new java.util.zip.ZipEntry(paths[i])); zip.write(text[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry(); }
            } catch (java.io.IOException e) { throw new FileNotFoundException(e.getMessage()); }
            return ParcelFileDescriptor.open(epub, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (!mode.equals("r") || directory(id) || !(id.startsWith("book/Disc ") || id.startsWith("library/New book/")) || !id.endsWith(".wav")) throw new FileNotFoundException(id);
        File file = new File(getContext().getCacheDir(), "Original-" + id.hashCode() + ".wav");
        ByteBuffer pcm = ByteBuffer.allocate(64044).order(ByteOrder.LITTLE_ENDIAN);
        pcm.put(new byte[]{'R','I','F','F'}).putInt(64036).put(new byte[]{'W','A','V','E','f','m','t',' '})
            .putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
            .put(new byte[]{'d','a','t','a'}).putInt(64000);
        for (int i = 0; i < 32000; i++) pcm.putShort((short)(Math.sin(i * 2 * Math.PI * 440 / 16000) * 500));
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(pcm.array()); }
        catch (java.io.IOException error) { throw new FileNotFoundException(error.getMessage()); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public boolean isChildDocument(String parent, String id) { return id.startsWith(parent + "/") || (parent.equals("library") && id.startsWith("book")); }
}
