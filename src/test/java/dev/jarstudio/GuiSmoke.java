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
        System.out.println("IDE: all ide feature checks done");
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
