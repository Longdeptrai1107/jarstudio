package dev.jarstudio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProcessRunner {
    public record Result(int exitCode,String output) {}
    private ProcessRunner() {}
    public static Result run(List<String> command,Path directory,Duration timeout) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(true);
        if(directory!=null)builder.directory(directory.toFile());
        Process process=builder.start();
        CompletableFuture<String> text=CompletableFuture.supplyAsync(()->{
            try(var input=process.getInputStream();var output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int count;
                while((count=input.read(buffer))>=0)if(output.size()<4*1024*1024)output.write(buffer,0,Math.min(count,4*1024*1024-output.size()));
                return output.toString(StandardCharsets.UTF_8);
            } catch(IOException error) {return error.toString();}
        });
        try {
            if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS))throw new IOException("Tác vụ mất quá lâu và đã được dừng. Thử class nhỏ hơn hoặc đổi bộ dịch ngược.");
            return new Result(process.exitValue(),text.get(10,TimeUnit.SECONDS));
        } finally {if(process.isAlive())process.destroyForcibly();}
    }
}
