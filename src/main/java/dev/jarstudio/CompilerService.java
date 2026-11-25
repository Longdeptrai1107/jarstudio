package dev.jarstudio;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class CompilerService {
    public record Diagnostic(String severity,int line,int column,String message){}
    public record Compiled(Map<String,byte[]> classes,List<Diagnostic> diagnostics,String output){}
    public static final class CompileFailure extends Exception {
        private final List<Diagnostic> diagnostics;
        public CompileFailure(String message,List<Diagnostic> diagnostics){super(message);this.diagnostics=diagnostics;}
        public List<Diagnostic> diagnostics(){return diagnostics;}
    }
    public Compiled compile(JarSession session,String entry,String source,List<Path> extraLibraries) throws Exception {
        return compileBatch(session,Map.of(entry,source),extraLibraries);
    }
    public Compiled compileBatch(JarSession session,Map<String,String> sources,List<Path> extraLibraries) throws Exception {
        if(sources.isEmpty())return new Compiled(Map.of(),List.of(),"");
        Map<String,Map<String,String>> groups=new LinkedHashMap<>();
        for(var entry:sources.entrySet()) {
            ClassTools.Info info=ClassTools.info(session.read(entry.getKey()));String suffix=info.internalName()+".class";
            if(!entry.getKey().endsWith(suffix))throw new CompileFailure("Đường dẫn class không trùng tên trong bytecode.",List.of());
            String prefix=entry.getKey().substring(0,entry.getKey().length()-suffix.length());
            groups.computeIfAbsent(info.major()+":"+prefix,key->new LinkedHashMap<>()).put(entry.getKey(),entry.getValue());
        }
        JarSession fork=new JarSession(session.input());var snapshot=session.snapshot();fork.stageAll(snapshot.changed());for(String entry:snapshot.deleted())fork.delete(entry);for(Path path:session.libraries())fork.addLibrary(path);
        Map<String,byte[]> output=new LinkedHashMap<>();List<Diagnostic> diagnostics=new ArrayList<>();StringBuilder notes=new StringBuilder();
        for(Map<String,String> group:groups.values()) {
            Compiled result=compileGroup(fork,group,extraLibraries);output.putAll(result.classes());fork.stageAll(result.classes());diagnostics.addAll(result.diagnostics());notes.append(result.output()).append('\n');
        }
        return new Compiled(Map.copyOf(output),List.copyOf(diagnostics),notes.toString());
    }
    private Compiled compileGroup(JarSession session,Map<String,String> sources,List<Path> extraLibraries) throws Exception {
        String entry=sources.keySet().iterator().next();ClassTools.Info expected=ClassTools.info(session.read(entry));
        if((expected.access()&org.objectweb.asm.Opcodes.ACC_MODULE)!=0)throw new CompileFailure("module-info cần cấu hình module riêng; chưa thể biên dịch bằng trình chỉnh sửa class.",List.of());
        String name=ClassTools.safeInternalName(expected.internalName());
        Path temporary=AppPaths.temporary("compile-");
        try {
            List<String> sourceFiles=new ArrayList<>();
            for(var source:sources.entrySet()) {
                String internal=ClassTools.safeInternalName(ClassTools.info(session.read(source.getKey())).internalName());
                Path file=temporary.resolve("source").resolve(internal+".java").normalize();Files.createDirectories(file.getParent());Files.writeString(file,source.getValue(),StandardCharsets.UTF_8);sourceFiles.add(file.toString());
            }
            Path output=temporary.resolve("classes");Files.createDirectories(output);
            Path workspace=session.materialize(temporary);
            List<Path> dependencies=new ArrayList<>();dependencies.add(workspace);dependencies.addAll(session.libraries());dependencies.addAll(extraLibraries);
            String cp=String.join(java.io.File.pathSeparator,dependencies.stream().map(Path::toString).distinct().toList());
            List<String> command=new ArrayList<>(List.of(AppPaths.javaExecutable().toString(),"-Xmx768m"));
            if(expected.major()<52) {
                command.addAll(List.of("-jar",AppPaths.tool("ecj.jar").toString(),"-source","1."+Math.max(3,expected.javaVersion()),"-target","1."+expected.javaVersion(),"-g","-proc:none","-encoding","UTF-8"));
                String boot=session.isJavaMe()?AppPaths.tool("cldcapi11.jar")+java.io.File.pathSeparator+AppPaths.tool("midpapi20.jar"):AppPaths.tool("legacy-java-base.jar").toString();
                command.addAll(List.of("-bootclasspath",boot,"-classpath",cp,"-d",output.toString(),"-maxProblems","300"));
            } else {
                int target=expected.javaVersion();
                if(target>Runtime.version().feature())throw new CompileFailure("Class này cần JDK "+target+". Hãy dùng bản EXE kèm runtime mới hơn.",List.of());
                command.addAll(List.of("-m","jdk.compiler/com.sun.tools.javac.Main","-encoding","UTF-8","-g","-proc:none","-implicit:none","--release",String.valueOf(target),"-classpath",cp,"-sourcepath",temporary.resolve("empty").toString(),"-d",output.toString()));
                if(expected.minor()==65535) {
                    if(target!=Runtime.version().feature())throw new CompileFailure("Class preview cần đúng phiên bản JDK "+target+" để biên dịch.",List.of());
                    command.add("--enable-preview");
                }
            }
            command.addAll(sourceFiles);
            ProcessRunner.Result result=ProcessRunner.run(command,temporary,Duration.ofSeconds(90));
            List<Diagnostic> diagnostics=parse(result.output());
            if(result.exitCode()!=0) {
                String detail=result.output().replace(temporary.toString(),"<workspace>");
                throw new CompileFailure("Biên dịch chưa thành công. Sửa các dòng báo lỗi bên dưới; nếu thiếu class, thêm JAR phụ thuộc.\n\n"+detail,diagnostics);
            }
            LinkedHashMap<String,byte[]> classes=new LinkedHashMap<>();
            String prefix=entry.substring(0,entry.length()-(name+".class").length());
            try(var paths=Files.walk(output)) {
                for(Path compiled:paths.filter(path->path.toString().endsWith(".class")).toList()) {
                    String relative=output.relativize(compiled).toString().replace('\\','/');byte[] bytes=Files.readAllBytes(compiled);
                    ClassTools.Info found=ClassTools.info(bytes);
                    if(found.major()!=expected.major()||(expected.major()==45&&found.minor()!=3))throw new CompileFailure("Compiler tạo sai phiên bản classfile, đã hủy thay đổi.",List.of());
                    classes.put(prefix+relative,bytes);
                }
            }
            if(!classes.keySet().containsAll(sources.keySet()))throw new CompileFailure("Tên package/class đã bị đổi. Giữ nguyên tên class khi biên dịch trong JAR.",List.of());
            return new Compiled(Map.copyOf(classes),diagnostics,result.output());
        } finally {AppPaths.deleteTree(temporary);}
    }
    private List<Diagnostic> parse(String output) {
        List<Diagnostic> diagnostics=new ArrayList<>();
        var javac=Pattern.compile("(?m)^.*?\\.java:(\\d+): (error|warning): (.+)$").matcher(output);
        while(javac.find())diagnostics.add(new Diagnostic(javac.group(2),Integer.parseInt(javac.group(1)),1,javac.group(3)));
        var ecj=Pattern.compile("(?m)^\\d+\\. (ERROR|WARNING) in .*?\\(at line (\\d+)\\)\\R([\\s\\S]*?)(?=\\R----------|\\z)").matcher(output);
        while(ecj.find()) {
            String[] lines=ecj.group(3).strip().split("\\R");
            diagnostics.add(new Diagnostic(ecj.group(1).toLowerCase(),Integer.parseInt(ecj.group(2)),1,lines.length==0?ecj.group(3):lines[lines.length-1]));
        }
        return diagnostics;
    }
}
