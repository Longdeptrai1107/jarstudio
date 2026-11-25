package dev.jarstudio;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import com.sun.jdi.request.StepRequest;

public final class IntegrationTests {
    static Path work;
    static final List<String> passed=new ArrayList<>();
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void done(String name){passed.add(name);System.out.println("PASS "+name);}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length>0?args[0]:"build/test-work").toAbsolutePath();Files.createDirectories(root);work=Files.createTempDirectory(root,"run-");
        Path fixture=fixture(work.resolve("fixture"));
        Path marker=work.resolve("static-initializer-ran");System.setProperty("jarstudio.test.marker",marker.toString());
        JarSession session=new JarSession(fixture);String original=AppPaths.hash(Files.readAllBytes(fixture));
        check(session.entries().stream().anyMatch(entry->entry.name().equals("sample/Demo.class")),"class index");
        check(!Files.exists(marker),"opening a JAR must not execute its classes");done("open-index-without-executing-code");
        DecompilerService service=new DecompilerService();
        String source=service.decompile(session,"sample/Demo.class",DecompilerService.Engine.CFR).source();
        check(source.contains("class Demo")&&source.contains("ORIGINAL"),"CFR source");
        String alternate=service.decompile(session,"sample/Demo.class",DecompilerService.Engine.VINEFLOWER).source();
        check(alternate.contains("class Demo")&&alternate.contains("ORIGINAL"),"Vineflower source");done("both-decompilers");
        check(ClassTools.compilationEntry(session,"sample/Demo$Nested.class").equals("sample/Demo.class"),"nested source resolves to enclosing compilation unit");
        CompilerService compiler=new CompilerService();
        var compiled=compiler.compile(session,"sample/Demo.class",source.replace("ORIGINAL","CHANGED"),List.of());
        check(compiled.classes().containsKey("sample/Demo.class"),"compiled root class");
        check(compiled.classes().containsKey("sample/Demo$Nested.class"),"compiled inner class");
        session.stageAll(compiled.classes());Path edited=work.resolve("edited.jar");ArchiveWriter.write(session.snapshot(),edited);
        check(run(edited,"sample.Demo").contains("CHANGED\n42"),"edited class execution");
        check(AppPaths.hash(Files.readAllBytes(fixture)).equals(original),"original JAR remains unchanged");
        check(resources(fixture).equals(resources(edited)),"all resources preserved");done("decompile-edit-compile-save-and-execute-java8");
        try {compiler.compile(new JarSession(fixture),"sample/Demo.class",source+"\nthis is not Java;\n",List.of());throw new AssertionError("compile should fail");}
        catch(CompilerService.CompileFailure expected){check(!expected.diagnostics().isEmpty(),"structured compiler diagnostics");}
        check(AppPaths.hash(Files.readAllBytes(fixture)).equals(original),"failed compile never damages original");done("compile-errors-preserve-original");
        JarSession constants=new JarSession(fixture);byte[] demo=constants.read("sample/Demo.class");
        var string=ClassTools.constants(demo).stream().filter(row->row.location().startsWith("text(")&&row.value().equals("ORIGINAL")).findFirst().orElseThrow();
        demo=ClassTools.replaceConstant(demo,string.key(),"PATCHED",false);
        var integer=ClassTools.constants(demo).stream().filter(row->row.location().startsWith("plus(")&&row.value().equals(1)).findFirst().orElseThrow();
        demo=ClassTools.replaceConstant(demo,integer.key(),"7",false);constants.stage("sample/Demo.class",demo);
        Path patched=work.resolve("constants.jar");ArchiveWriter.write(constants.snapshot(),patched);
        check(run(patched,"sample.Demo").contains("PATCHED\n48"),"constant patch execution");done("string-and-integer-bytecode-edit");
        constants.stage("sample/Demo.class",ClassTools.defaultReturn(demo,"plus","(I)I"));Path stub=work.resolve("default-return.jar");ArchiveWriter.write(constants.snapshot(),stub);
        check(run(stub,"sample.Demo").contains("PATCHED\n0"),"default return verifier and execution");done("method-body-default-return");
        List<SearchService.Hit> hits=new SearchService().search(List.of(new JarSession(fixture)),"ORIGINAL",100);
        check(hits.stream().anyMatch(hit->hit.kind().equals("String")),"string search");
        var method=new ClassTools.Member(true,"text","()Ljava/lang/String;",9,"");
        check(!new SearchService().references(List.of(new JarSession(fixture)),"sample/Demo",method).isEmpty(),"method references");done("search-and-direct-references");
        signed(fixture);
        JarSession files=new JarSession(fixture);files.stage("settings.properties","title=Tiếng Việt\n".getBytes(StandardCharsets.UTF_8));files.stage("added.txt","NEW".getBytes(StandardCharsets.UTF_8));files.delete("binary.bin");
        Path replaced=work.resolve("resources.jar");ArchiveWriter.write(files.snapshot(),replaced);
        try(ZipFile zip=new ZipFile(replaced.toFile())){check(zip.getEntry("added.txt")!=null&&zip.getEntry("binary.bin")==null,"add/delete files");}
        done("resource-add-edit-delete");
        try {files.stage("../../outside.txt",new byte[0]);throw new AssertionError("path traversal should fail");}catch(IllegalArgumentException expected){}done("reject-unsafe-entry-paths");
        Path changing=work.resolve("external.jar");Files.copy(fixture,changing);JarSession stale=new JarSession(changing);Files.write(changing,new byte[]{1},java.nio.file.StandardOpenOption.APPEND);
        try {stale.snapshot();throw new AssertionError("external change should be detected");}catch(java.io.IOException expected){}done("detect-external-file-change");
        modernRecord(compiler,service);
        batch(compiler);
        Path nro=Path.of("D:/NRO/source/dist/DragonBoy252-source-rebuilt.jar");if(Files.isRegularFile(nro))legacy(nro,compiler,service);
        debug();
        attach();
        Files.writeString(work.resolve("report.json"),"{\"passed\":true,\"checks\":"+passed.size()+",\"work\":\""+work.toString().replace("\\","/")+"\"}\n");
        System.out.println("ALL "+passed.size()+" integration checks passed. "+work);
    }
    static Path fixture(Path directory) throws Exception {
        Path source=directory.resolve("source/sample/Demo.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"""
            package sample;
            public class Demo {
                public static final String GREETING = "ORIGINAL";
                static { String marker=System.getProperty("jarstudio.test.marker"); if(marker!=null) try { java.nio.file.Files.write(java.nio.file.Paths.get(marker),new byte[]{1}); } catch(Exception error) { throw new RuntimeException(error); } }
                public static int plus(int value) { return value + 1; }
                public static String text() { return "ORIGINAL"; }
                public static void main(String[] args) { System.out.println(text()); System.out.println(plus(41)); }
                public static class Nested { public String value() { return "nested"; } }
            }
            """);
        Path classes=directory.resolve("classes");Files.createDirectories(classes);
        var result=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-m","jdk.compiler/com.sun.tools.javac.Main","--release","8","-g","-d",classes.toString(),source.toString()),directory,Duration.ofSeconds(30));check(result.exitCode()==0,result.output());
        Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Main-Class","sample.Demo");
        ByteArrayOutputStream mf=new ByteArrayOutputStream();manifest.write(mf);Path jar=directory.resolve("demo.jar");
        try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));zip.write(mf.toByteArray());zip.closeEntry();
            try(var paths=Files.walk(classes)){for(Path file:paths.filter(Files::isRegularFile).toList()){zip.putNextEntry(new ZipEntry(classes.relativize(file).toString().replace('\\','/')));zip.write(Files.readAllBytes(file));zip.closeEntry();}}
            zip.putNextEntry(new ZipEntry("settings.properties"));zip.write("title=original\n".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("binary.bin"));zip.write(new byte[]{0,1,2,3,(byte)255});zip.closeEntry();
        }
        return jar;
    }
    static Map<String,String> resources(Path jar) throws Exception {
        Map<String,String> map=new LinkedHashMap<>();try(ZipFile zip=new ZipFile(jar.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){var item=entries.nextElement();if(!item.isDirectory()&&!item.getName().endsWith(".class"))try(var input=zip.getInputStream(item)){map.put(item.getName(),AppPaths.hash(input.readAllBytes()));}}}return map;
    }
    static String run(Path jar,String main) throws Exception {
        var result=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-Xverify:all","-cp",jar.toString(),main),work,Duration.ofSeconds(20));check(result.exitCode()==0,result.output());return result.output().replace("\r\n","\n");
    }
    static void signed(Path fixture) throws Exception {
        Path signed=work.resolve("signed.jar");
        try(ZipFile original=new ZipFile(fixture.toFile());ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(signed))) {
            var entries=original.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();zip.putNextEntry(new ZipEntry(entry.getName()));try(var input=original.getInputStream(entry)){byte[] data=input.readAllBytes();if(entry.getName().equals("META-INF/MANIFEST.MF")){Manifest manifest=new Manifest(new java.io.ByteArrayInputStream(data));Attributes attrs=new Attributes();attrs.putValue("SHA-256-Digest","obsolete");manifest.getEntries().put("sample/Demo.class",attrs);ByteArrayOutputStream out=new ByteArrayOutputStream();manifest.write(out);data=out.toByteArray();}zip.write(data);}zip.closeEntry();}
            for(String name:List.of("META-INF/TEST.SF","META-INF/TEST.RSA")){zip.putNextEntry(new ZipEntry(name));zip.write("obsolete signature".getBytes());zip.closeEntry();}
        }
        JarSession session=new JarSession(signed);session.stage("new.txt",new byte[]{1});Path output=work.resolve("unsigned-edited.jar");ArchiveWriter.write(session.snapshot(),output);
        try(ZipFile zip=new ZipFile(output.toFile())) {
            check(zip.getEntry("META-INF/TEST.SF")==null&&zip.getEntry("META-INF/TEST.RSA")==null,"stale signatures removed");
            try(var input=zip.getInputStream(zip.getEntry("META-INF/MANIFEST.MF"))){Manifest manifest=new Manifest(input);check("sample.Demo".equals(manifest.getMainAttributes().getValue("Main-Class")),"main class retained");check(manifest.getEntries().isEmpty(),"stale per-entry digests removed");}
        }
        done("signed-jar-manifest-and-signature-cleanup");
    }
    static void modernRecord(CompilerService compiler,DecompilerService service) throws Exception {
        Path dir=work.resolve("record");Files.createDirectories(dir);Path source=dir.resolve("Person.java");Files.writeString(source,"package records; public record Person(String name) { public String greeting() { return \"Hello \" + name; } }");Path classes=dir.resolve("classes");Files.createDirectories(classes);
        var result=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-m","jdk.compiler/com.sun.tools.javac.Main","--release","17","-g","-d",classes.toString(),source.toString()),dir,Duration.ofSeconds(30));check(result.exitCode()==0,result.output());
        Path jar=dir.resolve("record.jar");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){zip.putNextEntry(new ZipEntry("records/Person.class"));zip.write(Files.readAllBytes(classes.resolve("records/Person.class")));zip.closeEntry();}
        JarSession session=new JarSession(jar);String java=service.decompile(session,"records/Person.class",DecompilerService.Engine.AUTO).source();check(java.contains("record Person"),java);
        var edited=compiler.compile(session,"records/Person.class",java.replace("Hello ","Welcome "),List.of());check(ClassTools.info(edited.classes().get("records/Person.class")).major()==61,"record target version");done("java17-record-decompile-and-recompile");
    }
    static void legacy(Path jar,CompilerService compiler,DecompilerService service) throws Exception {
        JarSession session=new JarSession(jar);String original=AppPaths.hash(Files.readAllBytes(jar));String entry="nro/MotherCanvas.class";
        String source=service.decompile(session,entry,DecompilerService.Engine.CFR).source();check(source.contains("microedition.platform"),"NRO source");
        var result=compiler.compile(session,entry,source.replace("microedition.platform","microedition.platform.debug"),List.of());
        var info=ClassTools.info(result.classes().get(entry));check(info.major()==45&&info.minor()==3,"CLDC classfile stays 45.3");
        session.stageAll(result.classes());Path output=work.resolve("nro-edited-test.jar");ArchiveWriter.write(session.snapshot(),output);check(resources(jar).equals(resources(output)),"488 NRO resources unchanged");
        check(AppPaths.hash(Files.readAllBytes(jar)).equals(original),"NRO original unchanged");done("actual-nro-cldc-1.1-midp-2.0-edit-rebuild");
    }
    static void debug() throws Exception {
        Path dir=work.resolve("debugger");Files.createDirectories(dir);Path source=dir.resolve("BreakTarget.java");
        Files.writeString(source,"package debug;\npublic class BreakTarget {\n public static String message(){ return \"OLD\"; }\n public static void main(String[] args){\n  int value=41; String text=null;\n  value++;\n  System.out.println(message()+\":\"+value);\n }\n}\n");
        var compiled=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-m","jdk.compiler/com.sun.tools.javac.Main","-g","--release","17","-d",dir.toString(),source.toString()),dir,Duration.ofSeconds(30));check(compiled.exitCode()==0,compiled.output());
        LinkedBlockingQueue<DebugController.Pause> pauses=new LinkedBlockingQueue<>();LinkedBlockingQueue<String> output=new LinkedBlockingQueue<>();
        DebugController controller=new DebugController(new DebugController.Listener(){public void state(DebugController.State value,String message){output.add(message);}public void output(String line){output.add(line);}public void paused(DebugController.Pause pause){pauses.add(pause);}});
        controller.toggle(DebugController.Breakpoint.line("debug.BreakTarget",6));
        try {
            controller.launch("debug.BreakTarget","",List.of(dir));DebugController.Pause first=pauses.poll(20,TimeUnit.SECONDS);check(first!=null,"breakpoint hit: "+output);
            check(first.frames().get(0).owner().equals("debug.BreakTarget"),"call stack owner");check(controller.watch("value",0).equals("41"),"watch reads local");
            var threads=controller.threads();var current=threads.stream().filter(DebugController.ThreadInfo::current).findFirst().orElseThrow();check(current.name().equals("main")&&current.suspended(),"paused main thread");check(!controller.selectThread(current.id()).frames().isEmpty(),"select thread frames");
            var text=first.variables().stream().filter(value->value.name().equals("text")).findFirst().orElseThrow();controller.set(text,"was null");check(controller.watch("text",0).equals("\"was null\""),"set null String using its declared type");
            var variable=first.variables().stream().filter(value->value.name().equals("value")).findFirst().orElseThrow();controller.set(variable,"55");check(controller.watch("value",0).equals("55"),"edit local");
            controller.step(StepRequest.STEP_OVER);DebugController.Pause second=pauses.poll(15,TimeUnit.SECONDS);check(second!=null,"step event");check(controller.watch("value",0).equals("56"),"step over increment");
            byte[] bytes=Files.readAllBytes(dir.resolve("debug/BreakTarget.class"));var literal=ClassTools.constants(bytes).stream().filter(row->row.value().equals("OLD")).findFirst().orElseThrow();
            byte[] edited=ClassTools.replaceConstant(bytes,literal.key(),"LIVE",false);check(controller.hotSwap(Map.of("debug/BreakTarget.class",edited))==1,"HotSwap one class");
            controller.resume();
            boolean expected=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<until){String line=output.poll(1,TimeUnit.SECONDS);if(line!=null&&line.contains("LIVE:56")){expected=true;break;}}
            check(expected,"running JVM used hot-swapped code: "+output);done("real-jdi-breakpoint-stack-locals-watch-edit-step-hotswap");
        } finally {controller.stop();}
    }
    static void batch(CompilerService compiler) throws Exception {
        Path dir=work.resolve("batch");Files.createDirectories(dir);Path a=dir.resolve("A.java"),b=dir.resolve("B.java");
        String sourceA="package batch; public class A { public static int value(){ return B.value(); } public static void main(String[] args){ System.out.println(value()); } }";
        String sourceB="package batch; public class B { public static int value(){ return 1; } }";
        Files.writeString(a,sourceA);Files.writeString(b,sourceB);Path classes=dir.resolve("classes");Files.createDirectories(classes);
        var result=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-m","jdk.compiler/com.sun.tools.javac.Main","--release","8","-g","-d",classes.toString(),a.toString(),b.toString()),dir,Duration.ofSeconds(30));check(result.exitCode()==0,result.output());
        Path jar=dir.resolve("batch.jar");try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(jar))){for(String name:List.of("batch/A.class","batch/B.class")){zip.putNextEntry(new ZipEntry(name));zip.write(Files.readAllBytes(classes.resolve(name)));zip.closeEntry();}}
        JarSession session=new JarSession(jar);var compiled=compiler.compileBatch(session,Map.of("batch/A.class",sourceA.replace("B.value()","B.value(40)"),"batch/B.class",sourceB.replace("int value(){ return 1; }","int value(int n){ return n + 2; }")),List.of());
        session.stageAll(compiled.classes());Path output=dir.resolve("edited.jar");ArchiveWriter.write(session.snapshot(),output);check(run(output,"batch.A").trim().equals("42"),"dependent source files compiled together");
        byte[] data=ClassTools.defaultReturn(session.read("batch/B.class"),"value","(I)I");var zero=ClassTools.constants(data).stream().filter(row->row.location().startsWith("value(")).findFirst().orElseThrow();
        data=ClassTools.replaceConstants(data,Map.of(zero.key(),"100000"));check(ClassTools.constants(data).stream().anyMatch(row->row.value().equals(100000)),"batch constant replacement");done("batch-compilation-of-dependent-source-files");
    }
    static void attach() throws Exception {
        Path dir=work.resolve("debugger");int port;try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        Process target=new ProcessBuilder(AppPaths.javaExecutable().toString(),"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:"+port,"-cp",dir.toString(),"debug.BreakTarget").redirectErrorStream(true).start();
        LinkedBlockingQueue<String> output=new LinkedBlockingQueue<>();Thread reader=new Thread(()->{try(var lines=new java.io.BufferedReader(new java.io.InputStreamReader(target.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=lines.readLine())!=null)output.add(line);}catch(Exception ignored){}},"test-attach-output");reader.setDaemon(true);reader.start();
        LinkedBlockingQueue<DebugController.Pause> pauses=new LinkedBlockingQueue<>();DebugController controller=new DebugController(new DebugController.Listener(){public void state(DebugController.State state,String message){}public void output(String text){output.add(text);}public void paused(DebugController.Pause pause){pauses.add(pause);}});
        try {
            String ready=output.poll(15,TimeUnit.SECONDS);check(ready!=null&&ready.contains("Listening for transport"),"JDWP listener ready: "+ready);
            controller.toggle(DebugController.Breakpoint.method("debug.BreakTarget","message","()Ljava/lang/String;"));controller.attach("127.0.0.1",port);
            var hit=pauses.poll(15,TimeUnit.SECONDS);check(hit!=null&&hit.frames().get(0).method().equals("message"),"attach method breakpoint");
            controller.stop();check(target.waitFor(15,TimeUnit.SECONDS)&&target.exitValue()==0,"detach resumes target without terminating it");done("jdi-attach-method-breakpoint-and-detach");
        } finally {controller.stop();if(target.isAlive())target.destroyForcibly();}
    }
}
