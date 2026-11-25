package dev.jarstudio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class AppPaths {
    public static final String VERSION="1.1.2";
    private AppPaths() {}
    public static Path home() {
        String override=System.getProperty("jarstudio.home");
        String local=System.getenv("LOCALAPPDATA");
        Path home=override!=null?Path.of(override):local!=null?Path.of(local,"JarStudio"):Path.of(System.getProperty("user.home"),".jarstudio");
        try {Files.createDirectories(home);} catch(IOException error) {throw new IllegalStateException("Không tạo được thư mục làm việc: "+home,error);}
        return home;
    }
    public static Path temporary(String prefix) throws IOException {
        Path directory=home().resolve("temp");Files.createDirectories(directory);return Files.createTempDirectory(directory,prefix);
    }
    public static Path javaExecutable() {
        String executable=System.getProperty("os.name").toLowerCase().contains("win")?"java.exe":"java";
        return Path.of(System.getProperty("java.home"),"bin",executable);
    }
    public static synchronized Path tool(String name) throws IOException {
        byte[] bytes;
        try(InputStream stream=AppPaths.class.getResourceAsStream("/tooling/"+name)) {
            if(stream==null)throw new IOException("Thiếu thành phần "+name+". Hãy giải nén đầy đủ bản phân phối ứng dụng.");
            bytes=stream.readAllBytes();
        }
        String hash=hash(bytes);
        Path directory=home().resolve("tools").resolve(hash.substring(0,16));Files.createDirectories(directory);
        Path file=directory.resolve(name);
        if(!Files.isRegularFile(file)||!hash(Files.readAllBytes(file)).equals(hash)) {
            Path part=Files.createTempFile(directory,"extract-",".tmp");Files.write(part,bytes);
            Files.move(part,file,StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }
    public static String hash(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception error) {throw new IllegalStateException(error);}
    }
    public static void deleteTree(Path directory) {
        if(directory==null||!Files.exists(directory))return;
        try(var paths=Files.walk(directory)) {for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        catch(IOException ignored) {}
    }
}
