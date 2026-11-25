package dev.jarstudio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

public final class JarSession {
    public record Entry(String name,long size,boolean directory) {
        public boolean isClass() {return !directory&&name.endsWith(".class");}
        public String simpleName() {return name.substring(name.lastIndexOf('/')+1);}
    }
    public record Snapshot(Path input,long size,FileTime modified,Map<String,byte[]> changed,Set<String> deleted) {}
    private Path input;
    private long originalSize;
    private FileTime originalTime;
    private final TreeMap<String,Entry> entries=new TreeMap<>();
    private final LinkedHashMap<String,byte[]> changes=new LinkedHashMap<>();
    private final LinkedHashSet<String> deleted=new LinkedHashSet<>();
    private final ArrayList<Path> libraries=new ArrayList<>();
    private Manifest manifest;
    private long revision;

    public JarSession(Path input) throws IOException {load(input);}
    private void load(Path path) throws IOException {
        path=path.toAbsolutePath().normalize();
        TreeMap<String,Entry> loaded=new TreeMap<>();Manifest loadedManifest=null;
        try(ZipFile zip=new ZipFile(path.toFile())) {
            var values=zip.entries();
            while(values.hasMoreElements()) {
                var item=values.nextElement();String name=item.getName();
                if(name.indexOf('\0')>=0||loaded.containsKey(name))throw new IOException("JAR có tên tệp trùng hoặc không hợp lệ: "+name);
                loaded.put(name,new Entry(name,item.getSize(),item.isDirectory()));
                if(loaded.size()>300000)throw new IOException("JAR có quá nhiều tệp để chỉnh sửa trong một workspace.");
            }
            var mf=zip.getEntry("META-INF/MANIFEST.MF");
            if(mf!=null)try(var stream=zip.getInputStream(mf)){loadedManifest=new Manifest(stream);}
        }
        input=path;originalSize=Files.size(path);originalTime=Files.getLastModifiedTime(path);
        entries.clear();entries.putAll(loaded);changes.clear();deleted.clear();manifest=loadedManifest;revision++;
        if(libraries.isEmpty()) {
            for(String folder:List.of("lib","libs")) {
                Path directory=path.getParent().resolve(folder);
                if(Files.isDirectory(directory))try(var files=Files.list(directory)) {
                    files.filter(Files::isRegularFile).filter(file->file.toString().toLowerCase().endsWith(".jar")).limit(300).forEach(libraries::add);
                }
            }
            if(manifest!=null) {
                String cp=manifest.getMainAttributes().getValue("Class-Path");
                if(cp!=null)for(String name:cp.trim().split("\\s+"))try {
                    java.net.URI resolved=path.toUri().resolve(name);
                    if("file".equalsIgnoreCase(resolved.getScheme())) {
                        Path dependency=Path.of(resolved);if(Files.isRegularFile(dependency)&&!libraries.contains(dependency))libraries.add(dependency);
                    }
                }catch(Exception ignored){}
            }
        }
    }
    public synchronized Path input() {return input;}
    public synchronized long revision() {return revision;}
    public synchronized String displayName() {return input.getFileName().toString();}
    public synchronized boolean dirty() {return !changes.isEmpty()||!deleted.isEmpty();}
    public synchronized int changedCount() {return changes.size()+deleted.size();}
    public synchronized List<Entry> entries() {
        TreeMap<String,Entry> current=new TreeMap<>(entries);
        for(String name:deleted)current.remove(name);
        changes.forEach((name,bytes)->current.put(name,new Entry(name,bytes.length,false)));
        return List.copyOf(current.values());
    }
    public synchronized List<Path> libraries() {return List.copyOf(libraries);}
    public synchronized void addLibrary(Path library) {
        Path value=library.toAbsolutePath().normalize();if(!libraries.contains(value)&&!value.equals(input))libraries.add(value);
    }
    public synchronized void removeLibrary(Path library) {libraries.remove(library);}
    public synchronized boolean isJavaMe() {
        return manifest!=null&&(manifest.getMainAttributes().getValue("MicroEdition-Profile")!=null||manifest.getMainAttributes().getValue("MIDlet-1")!=null);
    }
    public synchronized String mainClass() {return manifest==null?null:manifest.getMainAttributes().getValue("Main-Class");}
    public synchronized byte[] read(String name) throws IOException {
        if(deleted.contains(name))throw new IOException("Tệp đã được xóa trong workspace: "+name);
        if(changes.containsKey(name))return changes.get(name).clone();
        try(ZipFile zip=new ZipFile(input.toFile())) {
            var entry=zip.getEntry(name);if(entry==null)throw new IOException("Không tìm thấy tệp: "+name);
            if(entry.getSize()>64L*1024*1024)throw new IOException("Tệp lớn hơn 64 MB. Hãy xuất/thay tệp thay vì mở trong editor.");
            try(var stream=zip.getInputStream(entry)) {
                byte[] result=stream.readNBytes(64*1024*1024+1);
                if(result.length>64*1024*1024)throw new IOException("Tệp quá lớn để mở trong editor.");
                return result;
            }
        }
    }
    public synchronized void stage(String name,byte[] bytes) {
        validateEntry(name);changes.put(name,bytes.clone());deleted.remove(name);revision++;
    }
    public synchronized void stageAll(Map<String,byte[]> output) {output.forEach(this::stage);}
    public synchronized void delete(String name) {changes.remove(name);deleted.add(name);revision++;}
    public synchronized Snapshot snapshot() throws IOException {
        if(Files.size(input)!=originalSize||!Files.getLastModifiedTime(input).equals(originalTime))
            throw new IOException("Tệp JAR đã thay đổi ở bên ngoài ứng dụng. Mở lại JAR trước khi ghi để tránh mất dữ liệu.");
        return new Snapshot(input,originalSize,originalTime,new LinkedHashMap<>(changes),new LinkedHashSet<>(deleted));
    }
    public synchronized void acceptedSave(Path output) throws IOException {load(output);}
    public Path materialize(Path directory) throws Exception {
        Path file=directory.resolve("workspace.jar");ArchiveWriter.write(snapshot(),file);return file;
    }
    public static void validateEntry(String name) {
        if(name==null||name.isBlank()||name.startsWith("/")||name.indexOf('\0')>=0||name.indexOf('\\')>=0||name.indexOf(':')>=0)
            throw new IllegalArgumentException("Đường dẫn trong JAR không hợp lệ.");
        for(String part:name.split("/"))if(part.equals("..")||part.equals("."))throw new IllegalArgumentException("Đường dẫn trong JAR không an toàn.");
    }
    @Override public String toString(){return displayName()+(dirty()?" *":"");}
}
