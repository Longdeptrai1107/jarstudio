package dev.jarstudio;

import dev.jarstudio.ui.EditorTab;
import dev.jarstudio.ui.MainWindow;
import dev.jarstudio.ui.Ui;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchEngine;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

public final class GuiSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("jarstudio.test","true");Path directory=Path.of(args.length==0?"build/gui-smoke":args[0]).toAbsolutePath();Files.createDirectories(directory);IntegrationTests.work=directory;
        Path jar=IntegrationTests.fixture(directory.resolve("fixture"));AtomicReference<MainWindow> reference=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{Ui.install();MainWindow window=new MainWindow();window.setSize(1280,820);window.setLocationRelativeTo(null);window.setVisible(true);reference.set(window);});
        MainWindow window=reference.get();
        try {
            SwingUtilities.invokeAndWait(()->{
                Transferable dropped=new Transferable(){public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.javaFileListFlavor};}public boolean isDataFlavorSupported(DataFlavor flavor){return flavor.equals(DataFlavor.javaFileListFlavor);}public Object getTransferData(DataFlavor flavor){return List.of(jar.toFile());}};
                boolean accepted=window.getRootPane().getTransferHandler().importData(new TransferHandler.TransferSupport(window.getRootPane(),dropped));IntegrationTests.check(accepted,"JAR drop accepted");
            });
            AtomicReference<EditorTab> tabReference=new AtomicReference<>();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(50);
            while(System.nanoTime()<until){SwingUtilities.invokeAndWait(()->{if(!window.documents().isEmpty())tabReference.set(window.documents().get(0));});if(tabReference.get()!=null)break;Thread.sleep(100);}
            EditorTab tab=tabReference.get();IntegrationTests.check(tab!=null&&tab.source().contains("ORIGINAL"),"GUI decompiled source loaded");
            SwingUtilities.invokeAndWait(()->{
                byte[] data=new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf,'k','=', 'a','\r','\n'};
                EditorTab text=new EditorTab(tab.session,"test.properties",new EditorTab.Loaded(data,null,"","",""),window);
                IntegrationTests.check(!text.sourceDirty(),"opening CRLF resource is not an edit");IntegrationTests.check(java.util.Arrays.equals(data,text.resourceBytes()),"UTF-8 BOM and CRLF round trip");
                text.editor.setText("k=b\n");IntegrationTests.check(new String(text.resourceBytes(),java.nio.charset.StandardCharsets.UTF_8).equals("\ufeffk=b\r\n"),"CRLF and BOM retained after editing resource");
            });
            AtomicReference<CompletableFuture<Void>> compile=new AtomicReference<>();SwingUtilities.invokeAndWait(()->{tab.editor.setText(tab.source().replace("ORIGINAL","GUI_EDITED"));compile.set(window.compileTab(tab));});compile.get().get(45,TimeUnit.SECONDS);
            Path output=directory.resolve("gui-edited.jar");AtomicReference<CompletableFuture<Path>> save=new AtomicReference<>();SwingUtilities.invokeAndWait(()->save.set(window.saveTo(tab.session,output)));save.get().get(30,TimeUnit.SECONDS);
            IntegrationTests.check(IntegrationTests.run(output,"sample.Demo").contains("GUI_EDITED\n42"),"GUI edit/compile/save execution");
            ideFeatures(window,directory,tab);
            SwingUtilities.invokeAndWait(()->{
                IntegrationTests.check(window.explorer.getWidth()>200,"explorer width");IntegrationTests.check(tab.editor.getWidth()>500&&tab.editor.getHeight()>250,"code viewport size");
                BufferedImage image=new BufferedImage(window.getWidth(),window.getHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=image.createGraphics();window.printAll(graphics);graphics.dispose();
                try{ImageIO.write(image,"png",directory.resolve("JarStudio-window.png").toFile());}catch(Exception error){throw new RuntimeException(error);}
            });
            javaMeDebug(directory);
            Files.writeString(directory.resolve("result.json"),"{\"passed\":true,\"drop\":true,\"java_edit\":true,\"compile\":true,\"save\":true,\"resource_encoding\":true,\"java_me_debug\":true,\"layout_bounds\":true,\"ide_features\":true}\n");
            System.out.println("GUI smoke passed: "+directory);
        } finally {SwingUtilities.invokeAndWait(window::shutdownForTest);}
        System.exit(0);
    }
    static void ideFeatures(MainWindow window,Path directory,EditorTab tab) throws Exception {
        System.out.println("IDE: start external-file open");
        Path text=directory.resolve("note.txt");Files.writeString(text,"alpha beta gamma alpha\n",java.nio.charset.StandardCharsets.UTF_8);
        AtomicReference<EditorTab> fileTab=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->window.openFile(text));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<until){SwingUtilities.invokeAndWait(()->{for(EditorTab candidate:window.documents())if(candidate.fileExternal())fileTab.set(candidate);});if(fileTab.get()!=null)break;Thread.sleep(100);}
        IntegrationTests.check(fileTab.get()!=null&&fileTab.get().source().contains("gamma"),"external text file opens in a tab");
        System.out.println("IDE: external file opened, now editing + save");
        AtomicReference<Boolean> saved=new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(()->{fileTab.get().editor.setText("alpha beta delta delta\n");});
        SwingUtilities.invokeAndWait(()->{try{fileTab.get().saveFile();saved.set(true);}catch(Exception error){IntegrationTests.check(false,"save external file: "+error);}});
        IntegrationTests.check(saved.get()&&Files.readString(text,java.nio.charset.StandardCharsets.UTF_8).contains("delta"),"external text file saves to disk");
        System.out.println("IDE: saved ok, now find/replace/compile-error");
        AtomicReference<org.fife.ui.rtextarea.SearchResult> found=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{SearchContext find=new SearchContext("delta");find.setSearchForward(true);find.setSearchWrap(true);found.set(SearchEngine.find(fileTab.get().editor,find));});
        IntegrationTests.check(found.get()!=null&&found.get().wasFound(),"find in editor tab");
        AtomicReference<Integer> replaced=new AtomicReference<>(0);
        SwingUtilities.invokeAndWait(()->{SearchContext context=new SearchContext("delta");context.setReplaceWith("epsilon");context.setSearchForward(true);replaced.set(SearchEngine.replaceAll(fileTab.get().editor,context).getCount());});
        IntegrationTests.check(replaced.get()==2&&fileTab.get().source().contains("epsilon epsilon"),"replace all in editor tab");
        System.out.println("IDE: find/replace ok, now compile-error markers");
        int errorsBefore=tab.errorCount();
        AtomicReference<CompletableFuture<Void>> fail=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{tab.editor.setText("not java");fail.set(window.compileTab(tab));});
        boolean failed=false;try{fail.get().get(60,TimeUnit.SECONDS);}catch(Exception error){failed=true;}
        IntegrationTests.check(failed,"broken source fails compile");
        SwingUtilities.invokeAndWait(tab::clearErrorMarkers);
        IntegrationTests.check(tab.errorCount()==errorsBefore,"error markers clear without residue");
        Path dropped=directory.resolve("dropped.txt");Files.writeString(dropped,"dropped file content\n",java.nio.charset.StandardCharsets.UTF_8);
        AtomicReference<Boolean> dropAccepted=new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(()->{
            Transferable droppedTransferable=new Transferable(){public DataFlavor[] getTransferDataFlavors(){return new DataFlavor[]{DataFlavor.javaFileListFlavor};}public boolean isDataFlavorSupported(DataFlavor flavor){return flavor.equals(DataFlavor.javaFileListFlavor);}public Object getTransferData(DataFlavor flavor){return List.of(dropped.toFile());}};
            dropAccepted.set(window.getRootPane().getTransferHandler().importData(new TransferHandler.TransferSupport(window.getRootPane(),droppedTransferable)));
        });
        IntegrationTests.check(dropAccepted.get(),"dropping a plain text file is accepted");
        AtomicReference<EditorTab> droppedTab=new AtomicReference<>();long until2=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until2){SwingUtilities.invokeAndWait(()->{for(EditorTab candidate:window.documents())if(candidate.fileExternal()&&dropped.toAbsolutePath().normalize().equals(candidate.file.toAbsolutePath().normalize()))droppedTab.set(candidate);});if(droppedTab.get()!=null)break;Thread.sleep(100);}
        IntegrationTests.check(droppedTab.get()!=null&&droppedTab.get().source().contains("dropped file content"),"dropped text file opens in an editable tab");
        AtomicReference<Object> popup=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            try {
                java.lang.reflect.Method method=MainWindow.class.getDeclaredMethod("showCompletion",EditorTab.class);method.setAccessible(true);method.invoke(window,tab);
                java.lang.reflect.Field field=MainWindow.class.getDeclaredField("completionPopup");field.setAccessible(true);popup.set(field.get(window));
            } catch(Exception error){IntegrationTests.check(false,"completion popup: "+error);}
        });
        IntegrationTests.check(popup.get()!=null,"completion popup opens (Ctrl+Space)");
        if(popup.get()!=null){java.lang.reflect.Method close=popup.get().getClass().getDeclaredMethod("close");close.setAccessible(true);close.invoke(popup.get());}
        System.out.println("IDE: regression checks next");
        ideRegression(window,directory,tab,fileTab.get());
        System.out.println("IDE: all ide feature checks done");
    }
    static void ideRegression(MainWindow window,Path directory,EditorTab demoTab,EditorTab fileTab) throws Exception {
        // (1) completeWord: longer and shorter candidates must both produce the exact
        // suggestion (the shorter case used to corrupt the text). Caret sits at the end
        // of the typed prefix, which is how completion is actually invoked.
        SwingUtilities.invokeAndWait(()->{
            fileTab.editor.setText("hel");fileTab.editor.setCaretPosition(3);
            IntegrationTests.check(fileTab.wordStart()==0&&fileTab.caretWord().equals("hel"),"word boundary before caret");
            fileTab.completeWord("hello");
            IntegrationTests.check(fileTab.source().equals("hello"),"completion extends the typed prefix");
            fileTab.editor.setText("hello");fileTab.editor.setCaretPosition(5);
            fileTab.completeWord("he");
            IntegrationTests.check(fileTab.source().equals("he"),"completion with a shorter candidate rewrites the whole prefix");
            fileTab.editor.setCaretPosition(1);
            fileTab.completeWord(null);
            IntegrationTests.check(fileTab.source().equals("he"),"null completion is a no-op");
        });
        // (2) Outline must point at real declaration lines (class header, method, field).
        // The Demo tab was clobbered to "not java" by the compile-error test, so restore a
        // known, well-formatted source before reading the outline; otherwise -1== -1 is a
        // vacuous pass.
        String goodSource="package sample;\npublic class Demo {\n    public static final String GREETING = \"ORIGINAL\";\n    public static int plus(int value) {\n        return value + 1;\n    }\n}\n";
        final byte[][] classBytes=new byte[1][];
        try {classBytes[0]=demoTab.session.read(demoTab.entry);}catch(Exception error){classBytes[0]=new byte[0];}
        SwingUtilities.invokeAndWait(()->demoTab.replaceSource(goodSource,classBytes[0]));
        SwingUtilities.invokeAndWait(()->window.openEntry(demoTab.session,demoTab.entry,null));
        SwingUtilities.invokeAndWait(()->{
            try {java.lang.reflect.Method m=MainWindow.class.getDeclaredMethod("refreshOutline");m.setAccessible(true);m.invoke(window);}
            catch(Exception error){IntegrationTests.check(false,"refreshOutline: "+error);}
        });
        java.lang.reflect.Field mf=MainWindow.class.getDeclaredField("outlineModel");mf.setAccessible(true);
        var om=(javax.swing.table.TableModel)mf.get(window);
        IntegrationTests.check(om.getRowCount()>=3,"outline lists class + members");
        IntegrationTests.check(outlineLine(om,"Demo")==firstLine(goodSource,"class Demo"),"outline class row points at the class declaration");
        IntegrationTests.check(outlineLine(om,"plus")==firstLine(goodSource,"plus("),"outline method row points at the method declaration");
        IntegrationTests.check(outlineLine(om,"GREETING")==firstLine(goodSource,"GREETING"),"outline field row points at the field declaration");
        // (3) Recent-files menu must reflect what was opened (bug: built once at startup).
        java.lang.reflect.Field rfm=MainWindow.class.getDeclaredField("recentFilesMenu");rfm.setAccessible(true);
        AtomicReference<String> labels=new AtomicReference<>("");
        SwingUtilities.invokeAndWait(()->{
            try {javax.swing.JMenu menu=(javax.swing.JMenu)rfm.get(window);StringBuilder sb=new StringBuilder();for(int i=0;i<menu.getItemCount();i++)sb.append(menu.getItem(i).getText()).append('|');labels.set(sb.toString());}
            catch(Exception error){IntegrationTests.check(false,"read recent menu: "+error);}
        });
        IntegrationTests.check(labels.get().contains("note.txt"),"recent-files menu lists the opened text file");
        Path second=directory.resolve("second.txt");Files.writeString(second,"second content\n",java.nio.charset.StandardCharsets.UTF_8);
        SwingUtilities.invokeAndWait(()->window.openFile(second));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){final AtomicReference<Boolean> found=new AtomicReference<>(false);SwingUtilities.invokeAndWait(()->{for(EditorTab candidate:window.documents())if(candidate.fileExternal()&&second.toAbsolutePath().normalize().equals(candidate.file.toAbsolutePath().normalize()))found.set(true);});if(found.get())break;Thread.sleep(100);}
        SwingUtilities.invokeAndWait(()->{
            try {javax.swing.JMenu menu=(javax.swing.JMenu)rfm.get(window);StringBuilder sb=new StringBuilder();for(int i=0;i<menu.getItemCount();i++)sb.append(menu.getItem(i).getText()).append('|');labels.set(sb.toString());}
            catch(Exception error){IntegrationTests.check(false,"read recent menu: "+error);}
        });
        IntegrationTests.check(labels.get().contains("second.txt")&&labels.get().contains("note.txt"),"recent-files menu updates when more files open");
        // (4) F6 on an external tab must be a graceful no-op, not a crash.
        AtomicReference<CompletableFuture<Void>> compileExternal=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->compileExternal.set(window.compileTab(fileTab)));
        compileExternal.get().get(30,TimeUnit.SECONDS);
        IntegrationTests.check(true,"compile on external tab completes without error");
        // (5) Opening a missing file must not crash or create a tab.
        int[] docCount=new int[1];
        SwingUtilities.invokeAndWait(()->docCount[0]=window.documents().size());
        SwingUtilities.invokeAndWait(()->window.openFile(directory.resolve("does-not-exist.txt")));
        Thread.sleep(1000);
        int[] docCountAfter=new int[1];
        SwingUtilities.invokeAndWait(()->docCountAfter[0]=window.documents().size());
        IntegrationTests.check(docCountAfter[0]==docCount[0],"missing file does not add a tab or crash");
        // (6) An external .class file opens as a decompiled Java tab.
        Path srcDir=directory.resolve("extra-src");Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Extra.java"),"public class Extra { public String value() { return \"EXTRA\"; } }\n");
        Path classesDir=srcDir.resolve("classes");Files.createDirectories(classesDir);
        var compiled=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-m","jdk.compiler/com.sun.tools.javac.Main","--release","8","-g","-d",classesDir.toString(),srcDir.resolve("Extra.java").toString()),srcDir,java.time.Duration.ofSeconds(30));
        IntegrationTests.check(compiled.exitCode()==0,"helper class compiles for external .class test: "+compiled.output());
        Path classFile=classesDir.resolve("Extra.class");
        AtomicReference<EditorTab> classTab=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->window.openFile(classFile));
        long until2=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while(System.nanoTime()<until2){SwingUtilities.invokeAndWait(()->{for(EditorTab candidate:window.documents())if(candidate.fileExternal()&&candidate.isClass()&&classFile.toAbsolutePath().normalize().equals(candidate.file.toAbsolutePath().normalize()))classTab.set(candidate);});if(classTab.get()!=null)break;Thread.sleep(100);}
        IntegrationTests.check(classTab.get()!=null&&classTab.get().source().contains("class Extra"),"external .class opens as decompiled Java tab");
        // (7) Ctrl+S must save an external file even when no JAR is open (second window).
        AtomicReference<MainWindow> secondWindow=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->secondWindow.set(new MainWindow()));
        MainWindow w2=secondWindow.get();
        try {
            Path lone=directory.resolve("lone.txt");Files.writeString(lone,"before\n",java.nio.charset.StandardCharsets.UTF_8);
            SwingUtilities.invokeAndWait(()->w2.openFile(lone));
            AtomicReference<EditorTab> loneTab=new AtomicReference<>();
            long until3=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(System.nanoTime()<until3){SwingUtilities.invokeAndWait(()->{for(EditorTab candidate:w2.documents())if(candidate.fileExternal()&&lone.toAbsolutePath().normalize().equals(candidate.file.toAbsolutePath().normalize()))loneTab.set(candidate);});if(loneTab.get()!=null)break;Thread.sleep(100);}
            IntegrationTests.check(loneTab.get()!=null,"second window opens external file");
            SwingUtilities.invokeAndWait(()->loneTab.get().editor.setText("after save\n"));
            java.lang.reflect.Field sa=MainWindow.class.getDeclaredField("saveAction");sa.setAccessible(true);
            AtomicReference<Boolean> saveEnabled=new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(()->{
                try {
                    javax.swing.Action save=(javax.swing.Action)sa.get(w2);
                    saveEnabled.set(save.isEnabled());
                    save.actionPerformed(null);
                } catch(Exception error){IntegrationTests.check(false,"save action: "+error);}
            });
            IntegrationTests.check(saveEnabled.get(),"Ctrl+S is enabled when only an external file is open");
            IntegrationTests.check(Files.readString(lone,java.nio.charset.StandardCharsets.UTF_8).equals("after save\n"),"Ctrl+S saves the external file with no JAR open");
        } finally {
            SwingUtilities.invokeAndWait(w2::shutdownForTest);
        }
        // (8) Go to class (Ctrl+T) must select the matching class tab.
        AtomicReference<EditorTab> activeTab=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->window.gotoClass("sample.Demo"));
        long until4=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        boolean classSelected=false;
        while(System.nanoTime()<until4){SwingUtilities.invokeAndWait(()->activeTab.set(window.active()));classSelected=activeTab.get()==demoTab;if(classSelected)break;Thread.sleep(100);}
        IntegrationTests.check(classSelected,"go-to-class selects the class tab");
        int[] docCountGoto=new int[1];
        SwingUtilities.invokeAndWait(()->docCountGoto[0]=window.documents().size());
        SwingUtilities.invokeAndWait(()->window.gotoClass("definitely.missing.Xyz"));
        Thread.sleep(1000);
        int[] docCountGotoAfter=new int[1];
        SwingUtilities.invokeAndWait(()->docCountGotoAfter[0]=window.documents().size());
        IntegrationTests.check(docCountGotoAfter[0]==docCountGoto[0],"go-to-class with no match does not crash or add tabs");
        // (9) compileAll (Ctrl+Shift+F6) must compile every dirty class tab and stage the bytes.
        String batchedSource="package sample;\npublic class Demo {\n    public static final String GREETING = \"ORIGINAL\";\n    public static int plus(int value) { return value + 1; }\n    public static String text() { return \"BATCH_OK\"; }\n    public static void main(String[] args) { System.out.println(text()); System.out.println(plus(41)); }\n}\n";
        SwingUtilities.invokeAndWait(()->{
            demoTab.editor.setText(batchedSource);
            IntegrationTests.check(demoTab.dirty(),"class tab is dirty before compile-all");
            window.compileAll();
        });
        long until5=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        AtomicReference<Boolean> clean=new AtomicReference<>(true);
        while(System.nanoTime()<until5){SwingUtilities.invokeAndWait(()->clean.set(!demoTab.dirty()));if(clean.get())break;Thread.sleep(100);}
        IntegrationTests.check(clean.get(),"compile-all clears the dirty state of the edited class");
        AtomicReference<Boolean> staged=new AtomicReference<>(false);
        SwingUtilities.invokeAndWait(()->{
            try {staged.set(demoTab.session.snapshot().changed().containsKey(demoTab.entry));}
            catch(Exception error){IntegrationTests.check(false,"snapshot: "+error);}
        });
        IntegrationTests.check(staged.get(),"compile-all stages the compiled class into the session");
        // (10) runMain must compile any pending edits, build a runnable JAR and return its output.
        AtomicReference<CompletableFuture<String>> run=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->run.set(window.runMain("sample.Demo")));
        String output=run.get().get(90,TimeUnit.SECONDS);
        IntegrationTests.check(output.contains("BATCH_OK")&&output.contains("42"),"run-main executes the compiled class: "+output);
        System.out.println("IDE: regression checks done");
    }
    static int outlineLine(javax.swing.table.TableModel model,String name) {
        for(int i=0;i<model.getRowCount();i++) {
            if(name.equals(model.getValueAt(i,1))) {
                Object line=model.getValueAt(i,3);
                return line instanceof Integer number?number.intValue():-1;
            }
        }
        return -999;
    }
    static int firstLine(String source,String needle) {
        String[] lines=source.split("\n",-1);
        for(int i=0;i<lines.length;i++)if(lines[i].contains(needle))return i+1;
        return -1;
    }
    static void javaMeDebug(Path directory) throws Exception {
        Path dir=directory.resolve("midlet");Files.createDirectories(dir);Path file=dir.resolve("TinyMidlet.java");
        Files.writeString(file,"package smoke; public class TinyMidlet extends javax.microedition.midlet.MIDlet { public void startApp(){System.out.println(\"MIDLET_STARTED\");} public void pauseApp(){} public void destroyApp(boolean force){} }");Path classes=dir.resolve("classes");Files.createDirectories(classes);
        String boot=AppPaths.tool("cldcapi11.jar")+java.io.File.pathSeparator+AppPaths.tool("midpapi20.jar");
        var compiled=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-jar",AppPaths.tool("ecj.jar").toString(),"-source","1.3","-target","1.1","-bootclasspath",boot,"-g","-proc:none","-d",classes.toString(),file.toString()),dir,java.time.Duration.ofSeconds(30));IntegrationTests.check(compiled.exitCode()==0,compiled.output());
        Path jar=dir.resolve("tiny.jar");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))){zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));zip.write("Manifest-Version: 1.0\r\nMIDlet-1: Tiny,,smoke.TinyMidlet\r\nMIDlet-Name: Tiny\r\nMIDlet-Version: 1.0\r\nMIDlet-Vendor: JarStudio test\r\nMicroEdition-Configuration: CLDC-1.1\r\nMicroEdition-Profile: MIDP-2.0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();zip.putNextEntry(new java.util.zip.ZipEntry("smoke/TinyMidlet.class"));zip.write(Files.readAllBytes(classes.resolve("smoke/TinyMidlet.class")));zip.closeEntry();}
        var pauses=new java.util.concurrent.LinkedBlockingQueue<DebugController.Pause>();var lines=new java.util.concurrent.LinkedBlockingQueue<String>();DebugController controller=new DebugController(new DebugController.Listener(){public void state(DebugController.State state,String text){}public void output(String text){lines.add(text);}public void paused(DebugController.Pause pause){pauses.add(pause);}});
        try{controller.toggle(DebugController.Breakpoint.method("smoke.TinyMidlet","startApp","()V"));controller.launch("dev.jarstudio.tooling.MidletLauncher","--rms memory "+DebugController.quote(jar.toString()),List.of(AppPaths.tool("midlet-launcher.jar"),AppPaths.tool("microemulator.jar")));var hit=pauses.poll(30,TimeUnit.SECONDS);IntegrationTests.check(hit!=null&&hit.frames().get(0).owner().equals("smoke.TinyMidlet"),"MicroEmulator launches MIDlet and hits real breakpoint: "+lines);controller.resume();System.out.println("PASS java-me-microemulator-debug-launch");}
        finally{controller.stop();}
    }
}
