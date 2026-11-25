package dev.jarstudio.tooling;

/** Runs in the emulator JVM, isolated from JarStudio's ASM version. */
public final class MidletLauncher {
    public static void main(String[] args) throws Exception {
        org.microemu.app.Main.main(args);
        // MicroEmulator 2.0.4 opens a JAR at its MIDlet chooser by default.
        // Reuse its own launch path to start the selected MIDlet for F5.
        var instance=org.microemu.app.Common.class.getDeclaredField("instance");
        instance.setAccessible(true);
        var common=(org.microemu.app.Common)instance.get(null);
        javax.swing.SwingUtilities.invokeLater(()->common.initMIDlet(true));
    }
}
