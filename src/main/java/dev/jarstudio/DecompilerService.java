package dev.jarstudio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class DecompilerService {
    public enum Engine {AUTO,CFR,VINEFLOWER}
    public record Decompiled(String source,String engine,String notes){}
    public Decompiled decompile(JarSession session,String entry,Engine engine) throws Exception {
        byte[] data=session.read(entry);ClassTools.Info info=ClassTools.info(data);ClassTools.safeInternalName(info.internalName());
        if(engine==Engine.AUTO)engine=info.major()>65?Engine.VINEFLOWER:Engine.CFR;
        try {return execute(session,data,info,engine);}
        catch(Exception first) {
            if(Thread.currentThread().isInterrupted())throw first;
            Engine fallback=engine==Engine.CFR?Engine.VINEFLOWER:Engine.CFR;
            try {
                Decompiled result=execute(session,data,info,fallback);
                return new Decompiled(result.source(),result.engine(),"Đã dùng bộ dịch dự phòng: "+first.getMessage());
            }catch(Exception second){throw new IllegalStateException("Không dịch được class này. Vẫn có thể xem Bytecode/Hex hoặc sửa Hằng số.\n"+first.getMessage()+"\n"+second.getMessage(),second);}
        }
    }
    public Decompiled decompileFile(Path file,Engine engine) throws Exception {
        byte[] data=java.nio.file.Files.readAllBytes(file);
        ClassTools.Info info=ClassTools.info(data);
        return execute(null,data,info,engine==Engine.AUTO?Engine.CFR:engine);
    }
    private Decompiled execute(JarSession session,byte[] data,ClassTools.Info info,Engine engine) throws Exception {
        Path temporary=AppPaths.temporary("decompile-");
        try {
            Path input=temporary.resolve("input").resolve(info.internalName()+".class").normalize();
            if(!input.startsWith(temporary))throw new IllegalArgumentException("Đường dẫn class không hợp lệ.");
            Files.createDirectories(input.getParent());Files.write(input,data);
            Path output=temporary.resolve("source");Files.createDirectories(output);
            List<String> libraries=new ArrayList<>();
            if(session!=null) {
                Path resolved=session.materialize(temporary);libraries.add(resolved.toString());
                for(Path library:session.libraries())libraries.add(library.toString());
            }
            Path dir=input.toAbsolutePath().getParent();
            if(!libraries.contains(dir.toString()))libraries.add(dir.toString());
            List<String> command=new ArrayList<>(List.of(AppPaths.javaExecutable().toString(),"-Xmx768m","-jar",AppPaths.tool(engine==Engine.CFR?"cfr.jar":"vineflower.jar").toString()));
            if(engine==Engine.CFR)command.addAll(List.of(input.toString(),"--outputdir",output.toString(),"--extraclasspath",String.join(java.io.File.pathSeparator,libraries),"--comments","false","--showversion","false","--hideutf","false"));
            else {
                command.addAll(List.of("-log=WARN","-dgs=1","-rsy=0"));
                for(String library:libraries)command.add("-e="+library);
                command.add(input.toString());command.add(output.toString());
            }
            ProcessRunner.Result result=ProcessRunner.run(command,temporary,Duration.ofSeconds(60));
            List<Path> javaFiles;
            try(var paths=Files.walk(output)){javaFiles=paths.filter(path->path.toString().endsWith(".java")).toList();}
            String simple=info.internalName().substring(info.internalName().lastIndexOf('/')+1)+".java";
            Path file=javaFiles.stream().filter(path->path.getFileName().toString().equals(simple)).findFirst().orElse(javaFiles.isEmpty()?null:javaFiles.get(0));
            if(result.exitCode()!=0||file==null)throw new IllegalStateException(result.output().isBlank()?"Bộ dịch không tạo được Java source.":result.output());
            String source=Files.readString(file,StandardCharsets.UTF_8);
            if(source.isBlank())throw new IllegalStateException("Bộ dịch trả về source rỗng.");
            return new Decompiled(source,engine==Engine.CFR?"CFR":"Vineflower",result.output());
        } finally {AppPaths.deleteTree(temporary);}
    }
}
