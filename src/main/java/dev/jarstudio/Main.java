package dev.jarstudio;

import dev.jarstudio.ui.MainWindow;
import dev.jarstudio.ui.Ui;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public final class Main {
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            error.printStackTrace();
            SwingUtilities.invokeLater(()->JOptionPane.showMessageDialog(null,error.toString(),"JarStudio — lỗi",JOptionPane.ERROR_MESSAGE));
        });
        SwingUtilities.invokeLater(()->{
            Ui.install();MainWindow window=new MainWindow();window.setVisible(true);
            for(String argument:args)if(!argument.startsWith("--"))window.openPath(Path.of(argument),true);
        });
    }
}
