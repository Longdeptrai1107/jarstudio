package dev.jarstudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ArchiveWriter {
    private ArchiveWriter() {}
    public static boolean signature(String name) {
        String upper=name.toUpperCase(Locale.ROOT);
        if(!upper.startsWith("META-INF/"))return false;
        String file=upper.substring("META-INF/".length());
        return !file.contains("/")&&(file.endsWith(".SF")||file.endsWith(".RSA")||file.endsWith(".DSA")||file.endsWith(".EC")||file.startsWith("SIG-"));
    }
    public static byte[] withoutDigests(byte[] data) throws IOException {
        Manifest manifest=new Manifest(new java.io.ByteArrayInputStream(data));
        clean(manifest.getMainAttributes());manifest.getEntries().values().forEach(ArchiveWriter::clean);
        manifest.getEntries().entrySet().removeIf(entry->entry.getValue().isEmpty());
        ByteArrayOutputStream output=new ByteArrayOutputStream();manifest.write(output);return output.toByteArray();
    }
    private static void clean(Attributes attributes) {
        attributes.keySet().removeIf(key->{String name=key.toString().toUpperCase(Locale.ROOT);return name.contains("-DIGEST")||name.equals("SIGNATURE-VERSION")||name.equals("MAGIC");});
    }
    public static Path write(JarSession.Snapshot snapshot,Path destination) throws Exception {
        unchanged(snapshot);
        destination=destination.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path temporary=Files.createTempFile(destination.getParent(),".jarstudio-saving-",".jar");
        boolean edited=!snapshot.changed().isEmpty()||!snapshot.deleted().isEmpty();
        try {
            boolean signed;
            try(ZipFile original=new ZipFile(snapshot.input().toFile())) {signed=original.stream().anyMatch(entry->signature(entry.getName()));}
            try(ZipFile original=new ZipFile(snapshot.input().toFile());ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(temporary))) {
                output.setLevel(6);HashSet<String> written=new HashSet<>();
                var source=original.entries();
                while(source.hasMoreElements()) {
                    ZipEntry old=source.nextElement();String name=old.getName();
                    if(snapshot.deleted().contains(name)||(edited&&signature(name)))continue;
                    if(!written.add(name))throw new IOException("JAR có tệp trùng tên: "+name);
                    ZipEntry entry=new ZipEntry(name);if(old.getTime()>=0)entry.setTime(old.getTime());
                    if(old.getComment()!=null)entry.setComment(old.getComment());
                    output.putNextEntry(entry);
                    byte[] replacement=snapshot.changed().get(name);
                    if(name.equalsIgnoreCase("META-INF/MANIFEST.MF")&&edited&&signed) {
                        byte[] bytes=replacement;
                        if(bytes==null)try(var input=original.getInputStream(old)){bytes=input.readAllBytes();}
                        output.write(withoutDigests(bytes));
                    } else if(replacement!=null)output.write(replacement);
                    else if(!old.isDirectory())try(var input=original.getInputStream(old)){input.transferTo(output);}
                    output.closeEntry();
                }
                for(Map.Entry<String,byte[]> added:snapshot.changed().entrySet())if(!written.contains(added.getKey())&&!snapshot.deleted().contains(added.getKey())) {
                    JarSession.validateEntry(added.getKey());
                    if(edited&&signature(added.getKey()))continue;
                    output.putNextEntry(new ZipEntry(added.getKey()));output.write(added.getValue());output.closeEntry();
                }
            }
            try(ZipInputStream verify=new ZipInputStream(Files.newInputStream(temporary))) {
                byte[] buffer=new byte[32768];HashSet<String> names=new HashSet<>();ZipEntry entry;
                while((entry=verify.getNextEntry())!=null) {
                    if(!names.add(entry.getName()))throw new IOException("JAR đầu ra có tệp trùng tên.");
                    while(verify.read(buffer)>=0){}verify.closeEntry();
                }
            }
            unchanged(snapshot);
            try {Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException error) {Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}
            return destination;
        } finally {Files.deleteIfExists(temporary);}
    }
    private static void unchanged(JarSession.Snapshot snapshot) throws IOException {
        if(Files.size(snapshot.input())!=snapshot.size()||!Files.getLastModifiedTime(snapshot.input()).equals(snapshot.modified()))throw new IOException("JAR gốc đã thay đổi ở bên ngoài trong khi chuẩn bị lưu. Mở lại JAR để tránh ghi đè dữ liệu.");
    }
}
