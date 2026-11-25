package dev.jarstudio.ui;

import dev.jarstudio.AppPaths;
import dev.jarstudio.ArchiveWriter;
import dev.jarstudio.ClassTools;
import dev.jarstudio.CompilerService;
import dev.jarstudio.DebugController;
import dev.jarstudio.DecompilerService;
import dev.jarstudio.JarSession;
import dev.jarstudio.ProcessRunner;
import dev.jarstudio.SearchService;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import com.sun.jdi.request.StepRequest;
import org.fife.ui.rtextarea.SearchContext;
import org.fife.ui.rtextarea.SearchResult;

public final class MainWindow extends JFrame implements EditorTab.Callback {
    public record Item(JarSession session,String entry,ClassTools.Member member) {
        @Override public String toString(){if(member!=null)return member.display();return entry.substring(entry.lastIndexOf('/')+1);}
    }
    public record Folder(JarSession session,String prefix,String label){@Override public String toString(){return label;}}
    private record Edit(EditorTab tab,String source,byte[] resource,Map<String,String> constants){}
    private record Applied(Map<JarSession,Map<String,byte[]>> outputs,Map<EditorTab,String> sources){}
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread thread=new Thread(r,"jarstudio-work");thread.setDaemon(true);return thread;});
    private final LinkedHashMap<Path,JarSession> sessions=new LinkedHashMap<>();
    private final List<EditorTab> documents=new ArrayList<>();
    private final Map<JarSession,Path> savedOutputs=new LinkedHashMap<>();
    private final DefaultMutableTreeNode root=new DefaultMutableTreeNode("Workspace");
    private final DefaultTreeModel treeModel=new DefaultTreeModel(root);
    public final JTree explorer=new JTree(treeModel);
    public final JTabbedPane editors=new JTabbedPane();
    private final JTabbedPane tools=new JTabbedPane();
    private final JTextField filter=new JTextField();
    private final JTextField search=new JTextField();
    private final JTextArea output=new JTextArea();
    private final JLabel status=new JLabel("Sẵn sàng — kéo JAR vào cửa sổ để bắt đầu");
    private final JLabel position=new JLabel(" ");
    private final JProgressBar progress=new JProgressBar();
    private final DefaultTableModel resultModel=model("Loại","Class / tệp","Hàm","Chi tiết");
    private final JTable results=new JTable(resultModel);
    private final DefaultTableModel errorModel=model("Mức","Dòng","Thông báo");
    private final JTable errors=new JTable(errorModel);
    private final DefaultListModel<DebugController.Frame> stackModel=new DefaultListModel<>();
    private final JList<DebugController.Frame> stack=new JList<>(stackModel);
    private final DefaultMutableTreeNode localsRoot=new DefaultMutableTreeNode("Locals");
    private final DefaultTreeModel localsModel=new DefaultTreeModel(localsRoot);
    private final JTree locals=new JTree(localsModel);
    private final DefaultTableModel watchModel=model("Biểu thức","Giá trị");
    private final JTable watches=new JTable(watchModel);
    private final DefaultListModel<DebugController.Breakpoint> breakpointModel=new DefaultListModel<>();
    private final DefaultTableModel threadModel=model("ID","Thread","Trạng thái","Tạm dừng");
    private final JTable threadTable=new JTable(threadModel);
    private final DefaultTableModel outlineModel=model("Loại","Tên","Khuôn dạng","Dòng");
    private final JTable outline=new JTable(outlineModel);
    private final JComboBox<DecompilerService.Engine> engine=new JComboBox<>(DecompilerService.Engine.values());
    private final List<Path> recentFiles=new ArrayList<>();
    private final List<Path> recentJars=new ArrayList<>();
    private JMenu recentFilesMenu;
    private JMenu recentJarsMenu;
    private org.fife.ui.rtextarea.SearchContext findContext=new org.fife.ui.rtextarea.SearchContext();
    private int findNextCount;
    private final Preferences preferences=Preferences.userNodeForPackage(MainWindow.class);
    private final DecompilerService decompiler=new DecompilerService();
    private final CompilerService compiler=new CompilerService();
    public final DebugController debugger;
    private final Action openAction,saveAction,saveAsAction,compileAction,runAction,pauseAction,stopAction,stepIntoAction,stepOverAction,stepOutAction;
    private List<SearchService.Hit> searchHits=List.of();
    private int busy;
    private Future<?> currentJob;
    private boolean dark;
    private boolean changingStack;
    private int frameIndex;
    private EditorTab errorDocument;

    public MainWindow() {
        super("JarStudio — Java Assembly Editor");
        setIconImage(Ui.appImage());setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);setMinimumSize(new Dimension(1000,660));setSize(1360,880);setLocationRelativeTo(null);
        debugger=new DebugController(new DebugController.Listener(){
            public void state(DebugController.State state,String message){SwingUtilities.invokeLater(()->{log(message);status.setText(message);if(state!=DebugController.State.PAUSED)documents.forEach(EditorTab::clearExecutionLine);if(state==DebugController.State.DISCONNECTED){stackModel.clear();showVariables(List.of());threadModel.setRowCount(0);}updateActions();});}
            public void output(String message){SwingUtilities.invokeLater(()->log(message));}
            public void paused(DebugController.Pause pause){SwingUtilities.invokeLater(()->debugPaused(pause));}
        });
        openAction=action("Mở JAR…","open","control O",this::chooseOpen);
        saveAction=action("Lưu JAR","save","control S",()->saveSelected(false));
        saveAsAction=action("Lưu JAR thành…","saveall","control shift S",()->saveSelected(true));
        compileAction=action("Biên dịch class","compile","F6",this::compileSelected);
        runAction=action("Chạy / Tiếp tục","start","F5",this::startOrContinue);
        pauseAction=action("Tạm dừng","pause","control BREAK",()->debugJob(()->{debugger.pause();return null;}));
        stopAction=action("Dừng debug","stop","shift F5",()->debugger.stop());
        stepIntoAction=action("Step Into","into","F11",()->debugJob(()->{debugger.step(StepRequest.STEP_INTO);return null;}));
        stepOverAction=action("Step Over","step","F10",()->debugJob(()->{debugger.step(StepRequest.STEP_OVER);return null;}));
        stepOutAction=action("Step Out","out","shift F11",()->debugJob(()->{debugger.step(StepRequest.STEP_OUT);return null;}));
        setJMenuBar(menu());
        JPanel all=new JPanel(new BorderLayout());all.add(toolbar(),BorderLayout.NORTH);
        JSplitPane vertical=new JSplitPane(JSplitPane.VERTICAL_SPLIT,editors,toolWindows());vertical.setResizeWeight(.78);vertical.setDividerLocation(560);vertical.setBorder(null);
        JSplitPane horizontal=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,explorerPanel(),vertical);horizontal.setResizeWeight(.22);horizontal.setDividerLocation(285);horizontal.setBorder(null);
        all.add(horizontal,BorderLayout.CENTER);all.add(statusBar(),BorderLayout.SOUTH);setContentPane(all);
        welcome();
        editors.addChangeListener(event->{updateActions();updatePosition();refreshOutline();});
        TransferHandler drop=new TransferHandler(){
            public boolean canImport(TransferSupport support){return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);}
            @SuppressWarnings("unchecked") public boolean importData(TransferSupport support){
                if(!canImport(support))return false;
                try {
                    List<java.io.File> files=(List<java.io.File>)support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for(java.io.File file:files){
                        Path path=file.toPath();
                        if(Files.isDirectory(path))continue;
                        String name=path.toString().toLowerCase();
                        if(name.endsWith(".jar")||name.endsWith(".zip"))openPath(path,true);
                        else openFile(path);
                    }
                    return true;
                }
                catch(Exception error){showError(error);return false;}
            }
        };
        getRootPane().setTransferHandler(drop);explorer.setTransferHandler(drop);editors.setTransferHandler(drop);
        addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent event){closeWindow();}});
        getRootPane().registerKeyboardAction(event->toggleCurrentBreakpoint(),KeyStroke.getKeyStroke("F9"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event->findInEditor(),KeyStroke.getKeyStroke("control F"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event->replaceInEditor(),KeyStroke.getKeyStroke("control H"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event->nextFind(),KeyStroke.getKeyStroke("F3"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event->goToLine(),KeyStroke.getKeyStroke("control G"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event->openFile(),KeyStroke.getKeyStroke("control shift O"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(event->compileAll(),KeyStroke.getKeyStroke("control shift F6"),JComponent.WHEN_IN_FOCUSED_WINDOW);
        updateActions();
    }
    private static DefaultTableModel model(String... names){return new DefaultTableModel(names,0){public boolean isCellEditable(int row,int column){return false;}};}
    private Action action(String text,String icon,String shortcut,Runnable action) {
        AbstractAction result=new AbstractAction(text,Ui.icon(icon)){public void actionPerformed(ActionEvent event){action.run();}};
        if(shortcut!=null)result.putValue(Action.ACCELERATOR_KEY,KeyStroke.getKeyStroke(shortcut));
        result.putValue(Action.SHORT_DESCRIPTION,text+(shortcut==null?"":" ("+shortcut.replace("control","Ctrl")+")"));return result;
    }
    private JMenuBar menu() {
        JMenuBar bar=new JMenuBar();JMenu file=new JMenu("Tệp"),edit=new JMenu("Chỉnh sửa"),view=new JMenu("Xem"),source=new JMenu("Mã nguồn"),run=new JMenu("Chạy"),debug=new JMenu("Gỡ lỗi"),window=new JMenu("Cửa sổ"),help=new JMenu("Trợ giúp");
        file.add(action("Mở tệp… (txt, java, …)","open","control shift O",this::openFile));
        file.add(recentMenu());
        file.add(recentJarMenu());
        file.addSeparator();
        file.add(openAction);file.add(saveAction);file.add(saveAsAction);file.addSeparator();
        file.add(action("Xuất Java / tài nguyên đang xem…","export",null,this::exportSelected));
        file.add(action("Đóng tab","close",null,()->{EditorTab tab=active();if(tab!=null)closeTab(tab);}));
        file.add(action("Đóng JAR đang chọn","close",null,this::closeSession));file.addSeparator();file.add(action("Thoát",null,"alt F4",this::closeWindow));
        edit.add(action("Hoàn tác","undo","control Z",()->{EditorTab tab=active();if(tab!=null)tab.editor.undoLastAction();}));
        edit.add(action("Làm lại","redo","control Y",()->{EditorTab tab=active();if(tab!=null)tab.editor.redoLastAction();}));edit.addSeparator();
        edit.add(compileAction);edit.add(action("Biên dịch mọi class đang sửa…","compile","control shift F6",this::compileAll));
        edit.add(action("Tải lại Java từ class","refresh",null,this::reloadSelected));
        edit.add(action("Thay tệp…","open",null,this::replaceFile));edit.add(action("Thêm tệp vào JAR…","open",null,this::addFile));edit.addSeparator();
        edit.add(action("Tìm trong code…","search","control F",this::findInEditor));
        edit.add(action("Tìm và thay…","search","control H",this::replaceInEditor));
        edit.add(action("Kết quả tiếp theo",null,"F3",this::nextFind));
        edit.add(action("Đến dòng…",null,"control G",this::goToLine));
        source.add(action("Đi đến class… (Ctrl+T)","class","control T",this::gotoClass));
        source.add(action("Bảng cấu trúc class","class",null,this::showOutline));
        source.add(action("Gom/hủy gom code (Ctrl+M)",null,"control M",this::toggleFolds));
        source.add(action("Bình luận dòng (Ctrl+/, không cần phím tắt)",null,null,this::toggleComment));
        run.add(action("Chạy class main…","start",null,this::runMain));
        run.add(runAction);
        view.add(action("Tìm class / hàm / chuỗi","search","control shift F",()->{tools.setSelectedIndex(1);search.requestFocusInWindow();}));
        view.add(action("Tìm nơi sử dụng","analyze","control R",this::analyzeSelected));view.addSeparator();
        JCheckBoxMenuItem theme=new JCheckBoxMenuItem("Editor tối");theme.addActionListener(event->{dark=theme.isSelected();documents.forEach(tab->tab.theme(dark));});view.add(theme);
        view.add(action("Phóng to code",null,"control EQUALS",()->fontSize(1)));view.add(action("Thu nhỏ code",null,"control MINUS",()->fontSize(-1)));
        debug.add(runAction);debug.add(pauseAction);debug.add(stopAction);debug.addSeparator();debug.add(stepIntoAction);debug.add(stepOverAction);debug.add(stepOutAction);debug.addSeparator();
        debug.add(action("Bật/tắt breakpoint","breakpoint","F9",this::toggleCurrentBreakpoint));
        debug.add(action("Ngắt khi vào hàm đang chọn","breakpoint",null,this::methodBreakpoint));
        debug.add(action("Gắn vào JVM…","attach",null,this::attach));debug.add(action("Áp dụng code vào JVM đang chạy","compile",null,this::hotSwap));
        window.add(action("Output",null,null,()->tools.setSelectedIndex(0)));window.add(action("Call Stack",null,null,()->tools.setSelectedIndex(3)));window.add(action("Locals",null,null,()->tools.setSelectedIndex(4)));window.add(action("Watch",null,null,()->tools.setSelectedIndex(5)));window.add(action("Breakpoints",null,null,()->tools.setSelectedIndex(6)));
        window.add(action("Threads",null,null,()->{tools.setSelectedIndex(7);refreshThreads();}));
        window.add(action("Cấu trúc (outline)","class",null,this::showOutline));
        window.addSeparator();window.add(action("Thư viện phụ thuộc…","library",null,this::libraries));
        help.add(action("Hướng dẫn nhanh",null,"F1",()->JOptionPane.showMessageDialog(this,"1. Kéo JAR vào cửa sổ hoặc Mở tệp (txt/java)…\n2. Sửa Java hoặc tab Hằng số.\n3. F6 biên dịch class; Ctrl+Shift+F6 biên dịch mọi class đang sửa.\n4. Ctrl+S lưu JAR; F5 debug.\n\nCtrl+T: đi đến class. Ctrl+F/H: tìm / thay trong tab.\nF9 breakpoint, F10/F11 step. Bảng Cấu trúc liệt kê class + hàm.\nBiên dịch Java đang xem để đồng bộ số dòng debug.","JarStudio",JOptionPane.INFORMATION_MESSAGE)));
        help.add(action("Về JarStudio",null,null,()->JOptionPane.showMessageDialog(this,"JarStudio "+AppPaths.VERSION+"\nJava JAR editor và debugger, bố cục quen thuộc kiểu dnSpy.\n\nJava "+Runtime.version()+"\nCFR + Vineflower · ASM · RSyntaxTextArea\n\nChỉnh sửa Java/bytecode của JAR; không phải debugger .NET.\nKhông tuyên bố tương đương 99% chức năng dnSpy.","Về JarStudio",JOptionPane.INFORMATION_MESSAGE)));
        for(JMenu item:List.of(file,edit,view,source,run,debug,window,help))bar.add(item);return bar;
    }
    private JMenu recentMenu() {
        JMenu recent=new JMenu("Tệp gần đây");
        recentFilesMenu=recent;
        recent.addMenuListener(new javax.swing.event.MenuListener(){public void menuSelected(javax.swing.event.MenuEvent event){rebuildRecentMenu(recent,recentFiles,MainWindow.this::openFile);}public void menuDeselected(javax.swing.event.MenuEvent event){}public void menuCanceled(javax.swing.event.MenuEvent event){}});
        rebuildRecentMenu(recent,recentFiles,this::openFile);
        return recent;
    }
    private JMenu recentJarMenu() {
        JMenu recent=new JMenu("JAR gần đây");
        recentJarsMenu=recent;
        recent.addMenuListener(new javax.swing.event.MenuListener(){public void menuSelected(javax.swing.event.MenuEvent event){rebuildRecentMenu(recent,recentJars,path->openPath(path,true));}public void menuDeselected(javax.swing.event.MenuEvent event){}public void menuCanceled(javax.swing.event.MenuEvent event){}});
        rebuildRecentMenu(recent,recentJars,path->openPath(path,true));
        return recent;
    }
    private void rebuildRecentMenu(JMenu menu,List<Path> items,java.util.function.Consumer<Path> opener) {
        menu.removeAll();
        if(items.isEmpty())menu.add(new JMenuItem("(chưa có)"));
        for(Path path:items)menu.add(action(path.getFileName().toString(),null,null,()->opener.accept(path)));
    }
    private void refreshRecentMenus() {
        if(recentFilesMenu!=null)rebuildRecentMenu(recentFilesMenu,recentFiles,this::openFile);
        if(recentJarsMenu!=null)rebuildRecentMenu(recentJarsMenu,recentJars,path->openPath(path,true));
    }
    private JComponent toolbar() {
        JToolBar bar=new JToolBar();bar.setFloatable(false);bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0,0,1,0,new Color(190,196,204)),BorderFactory.createEmptyBorder(3,5,3,5)));
        addTool(bar,openAction);addTool(bar,action("Mở tệp","open","control shift O",this::openFile));addTool(bar,saveAction);addTool(bar,saveAsAction);bar.addSeparator();
        JButton compile=new JButton(compileAction);compile.setFocusable(false);compile.setToolTipText("Biên dịch class đang mở (F6)");bar.add(compile);
        JButton compileAll=new JButton(action("Biên dịch mọi class đang sửa","compile","control shift F6",this::compileAll));compileAll.setText("");compileAll.setFocusable(false);compileAll.setToolTipText("Biên dịch mọi class đang sửa (Ctrl+Shift+F6)");bar.add(compileAll);bar.addSeparator();
        for(Action action:List.of(runAction,pauseAction,stopAction,stepIntoAction,stepOverAction,stepOutAction))addTool(bar,action);
        bar.addSeparator();JButton runMain=Ui.button("Chạy class main…","start",this::runMain);bar.add(runMain);bar.addSeparator();
        JButton gotoClass=Ui.button("Đi đến class…","class",this::gotoClass);bar.add(gotoClass);bar.addSeparator();
        JButton libraries=Ui.button("Thư viện…","library",this::libraries);bar.add(libraries);bar.add(Box.createHorizontalGlue());
        bar.add(new JLabel("Bộ dịch: "));engine.setMaximumSize(new Dimension(130,25));engine.setToolTipText("AUTO tự chọn bộ dịch phù hợp. Đổi rồi Tải lại Java nếu class khó đọc.");bar.add(engine);bar.add(Box.createHorizontalStrut(6));
        return bar;
    }
    private static void addTool(JToolBar toolbar,Action action){JButton button=toolbar.add(action);button.setText("");button.setFocusable(false);button.setPreferredSize(new Dimension(30,28));}
    private JComponent explorerPanel() {
        JPanel panel=new JPanel(new BorderLayout());JPanel north=new JPanel(new BorderLayout());north.add(Ui.header("Assembly Explorer"),BorderLayout.NORTH);
        JPanel find=new JPanel(new BorderLayout(4,0));find.setBorder(BorderFactory.createEmptyBorder(5,5,5,5));find.add(new JLabel(Ui.icon("search")),BorderLayout.WEST);filter.setToolTipText("Lọc class hoặc đường dẫn tài nguyên");find.add(filter);north.add(find,BorderLayout.SOUTH);panel.add(north,BorderLayout.NORTH);
        explorer.setRootVisible(false);explorer.setShowsRootHandles(true);explorer.setRowHeight(23);explorer.setBorder(BorderFactory.createEmptyBorder(3,3,3,0));explorer.setCellRenderer(new DefaultTreeCellRenderer(){public java.awt.Component getTreeCellRendererComponent(JTree tree,Object value,boolean selected,boolean expanded,boolean leaf,int row,boolean focus){
            super.getTreeCellRendererComponent(tree,value,selected,expanded,leaf,row,focus);Object object=((DefaultMutableTreeNode)value).getUserObject();
            setIcon(Ui.icon(object instanceof JarSession?"archive":object instanceof Folder?"package":object instanceof Item item?item.member()!=null?(item.member().method()?"method":"field"):item.entry().endsWith(".class")?"class":"file":"file"));return this;
        }});
        explorer.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(event.getClickCount()==2){Item item=selectedItem();if(item!=null)openEntry(item.session(),item.entry(),item.member()==null?null:item.member().name());}}public void mousePressed(MouseEvent event){popup(event);}public void mouseReleased(MouseEvent event){popup(event);}});
        explorer.addTreeWillExpandListener(new TreeWillExpandListener(){public void treeWillExpand(TreeExpansionEvent event){DefaultMutableTreeNode node=(DefaultMutableTreeNode)event.getPath().getLastPathComponent();if(node.getUserObject() instanceof Item item&&item.member()==null&&item.entry().endsWith(".class")&&node.getChildCount()==1&&node.getFirstChild().toString().equals("…"))loadMembers(node,item);}public void treeWillCollapse(TreeExpansionEvent event){}});
        filter.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){rebuildTree();}public void removeUpdate(DocumentEvent e){rebuildTree();}public void changedUpdate(DocumentEvent e){rebuildTree();}});
        panel.add(new JScrollPane(explorer),BorderLayout.CENTER);return panel;
    }
    private void popup(MouseEvent event) {
        if(!event.isPopupTrigger())return;TreePath path=explorer.getPathForLocation(event.getX(),event.getY());if(path!=null)explorer.setSelectionPath(path);
        Item item=selectedItem();JPopupMenu menu=new JPopupMenu();
        if(item!=null) {
            menu.add(action("Mở / sửa Java","class",null,()->openEntry(item.session(),item.entry(),item.member()==null?null:item.member().name())));
            if(item.entry().endsWith(".class"))menu.add(action("Bytecode / Hằng số class này","field",null,()->openRawEntry(item.session(),item.entry(),null).thenAccept(tab->tab.views.setSelectedIndex(2))));
            menu.add(action("Tìm nơi sử dụng","analyze",null,this::analyzeSelected));
            if(item.member()!=null&&item.member().method()) {
                menu.add(action("Ngắt khi vào hàm","breakpoint",null,this::methodBreakpoint));
                menu.add(action("Thay hàm bằng giá trị mặc định…","compile",null,this::defaultReturn));
            }
            menu.addSeparator();menu.add(action("Thay tệp…","open",null,this::replaceFile));menu.add(action("Xóa tệp khỏi JAR…","close",null,this::deleteSelected));
        }
        menu.add(action("Thư viện phụ thuộc…","library",null,this::libraries));menu.show(explorer,event.getX(),event.getY());
    }
    private JComponent toolWindows() {
        output.setFont(Ui.CODE.deriveFont(12f));output.setEditable(false);output.setBackground(Color.WHITE);output.setBorder(BorderFactory.createEmptyBorder(5,7,5,7));tools.addTab("Output",new JScrollPane(output));
        JPanel find=new JPanel(new BorderLayout());JPanel findBar=new JPanel(new BorderLayout(5,0));findBar.setBorder(BorderFactory.createEmptyBorder(4,5,4,5));findBar.add(search);findBar.add(Ui.button("Tìm","search",this::searchAll),BorderLayout.EAST);find.add(findBar,BorderLayout.NORTH);results.setRowHeight(23);results.setAutoCreateRowSorter(true);find.add(new JScrollPane(results));tools.addTab("Tìm kiếm / Analyze",find);search.addActionListener(event->searchAll());
        results.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(event.getClickCount()==2&&results.getSelectedRow()>=0){int row=results.convertRowIndexToModel(results.getSelectedRow());if(row<searchHits.size()){var hit=searchHits.get(row);openEntry(hit.session(),hit.entry(),hit.member());}}}});
        errors.setRowHeight(23);tools.addTab("Lỗi biên dịch",new JScrollPane(errors));errors.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){int row=errors.getSelectedRow();if(row>=0&&errorDocument!=null){editors.setSelectedComponent(errorDocument);errorDocument.jumpLine(((Number)errorModel.getValueAt(row,1)).intValue());}}});
        stack.setFont(Ui.FONT);stack.addListSelectionListener(event->{if(!event.getValueIsAdjusting()&&!changingStack&&stack.getSelectedValue()!=null){frameIndex=stack.getSelectedValue().index();debugJob(()->{DebugController.Pause pause=debugger.inspect(frameIndex,"Frame");SwingUtilities.invokeLater(()->showVariables(pause.variables()));return null;});}});tools.addTab("Call Stack",new JScrollPane(stack));
        locals.setRootVisible(false);locals.setShowsRootHandles(true);locals.setRowHeight(23);locals.addTreeWillExpandListener(new TreeWillExpandListener(){public void treeWillExpand(TreeExpansionEvent event){DefaultMutableTreeNode node=(DefaultMutableTreeNode)event.getPath().getLastPathComponent();if(node.getUserObject() instanceof DebugController.Variable value&&node.getChildCount()==1&&node.getFirstChild().toString().equals("…"))debugJob(()->{List<DebugController.Variable> children=debugger.children(value);SwingUtilities.invokeLater(()->{node.removeAllChildren();for(var child:children)node.add(variableNode(child));localsModel.reload(node);});return null;});}public void treeWillCollapse(TreeExpansionEvent event){}});
        locals.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(event.getClickCount()==2)editVariable();}});tools.addTab("Locals",new JScrollPane(locals));
        JPanel watch=new JPanel(new BorderLayout());watch.add(new JScrollPane(watches));JPanel buttons=new JPanel(new FlowLayout(FlowLayout.LEFT,4,3));buttons.add(Ui.button("Thêm Watch","search",()->{String expression=JOptionPane.showInputDialog(MainWindow.this,"Biến hoặc đường dẫn field (ví dụ: this.name):");if(expression!=null&&!expression.isBlank()){watchModel.addRow(new Object[]{expression,""});refreshWatches();}}));buttons.add(Ui.button("Xóa","close",()->{if(watches.getSelectedRow()>=0)watchModel.removeRow(watches.getSelectedRow());}));watch.add(buttons,BorderLayout.SOUTH);tools.addTab("Watch",watch);
        JList<DebugController.Breakpoint> breakpoints=new JList<>(breakpointModel);breakpoints.setFont(Ui.FONT);tools.addTab("Breakpoints",new JScrollPane(breakpoints));
        JPanel threads=new JPanel(new BorderLayout());threadTable.setRowHeight(23);threads.add(new JScrollPane(threadTable));JPanel threadButtons=new JPanel(new FlowLayout(FlowLayout.LEFT,4,3));threadButtons.add(Ui.button("Làm mới","refresh",this::refreshThreads));threads.add(threadButtons,BorderLayout.SOUTH);tools.addTab("Threads",threads);
        threadTable.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(event.getClickCount()==2&&threadTable.getSelectedRow()>=0){long id=((Number)threadModel.getValueAt(threadTable.convertRowIndexToModel(threadTable.getSelectedRow()),0)).longValue();debugJob(()->{var selected=debugger.selectThread(id);SwingUtilities.invokeLater(()->debugPaused(selected));return null;});}}});
        outline.setRowHeight(23);outline.setAutoCreateRowSorter(true);tools.addTab("Cấu trúc",new JScrollPane(outline));
        outline.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){if(event.getClickCount()==2&&outline.getSelectedRow()>=0){int model=outline.convertRowIndexToModel(outline.getSelectedRow());if(model<outlineRows.size()){OutlineRow row=outlineRows.get(model);var tab=active();JarSession session=(tab!=null&&row.owner.equals(tab.entry)&&tab.session!=null)?tab.session:selectedSession();if(session==null)return;if(tab!=null&&row.owner.equals(tab.entry)){if(row.classHeader()||row.line()<0)tab.jump(row.member);else tab.jumpLine(row.line());}else openEntry(session,row.entry,row.classHeader()?null:row.member);}}}});
        return tools;
    }
    private final List<OutlineRow> outlineRows=new ArrayList<>();
    private record OutlineRow(String entry,String owner,String member,int line,boolean classHeader){}
    private void showOutline(){tools.setSelectedIndex(8);refreshOutline();}
    private void refreshOutline(){
        var tab=active();outlineRows.clear();outlineModel.setRowCount(0);
        if(tab==null||tab.info==null||tab.source()==null)return;
        String[] lines=tab.source().split("\n",-1);
        String simpleName=tab.info.internalName().substring(tab.info.internalName().lastIndexOf('/')+1);
        int classLine=lineOfDeclaration(lines,simpleName,true);
        outlineModel.addRow(new Object[]{"Class",simpleName,"",classLine<0?"":classLine});
        outlineRows.add(new OutlineRow(tab.entry,tab.entry,simpleName,classLine,true));
        for(ClassTools.Member member:tab.info.members()) {
            if(member.name().equals("<init>")||member.name().equals("<clinit>"))continue;
            int line=lineOfDeclaration(lines,member.name(),!member.method());
            outlineModel.addRow(new Object[]{member.method()?"Hàm":"Field",member.name(),member.descriptor(),line<0?"":line});
            outlineRows.add(new OutlineRow(tab.entry,tab.entry,member.name(),line,false));
        }
    }
    private int lineOfDeclaration(String[] lines,String name,boolean fieldOrClass) {
        if(name==null||name.isBlank())return -1;
        String quoted=java.util.regex.Pattern.quote(name);
        if(fieldOrClass) {
            // Class header may carry modifiers ("public class X"); field is "TYPE NAME = ...;" or "TYPE NAME;".
            java.util.regex.Pattern classPattern=java.util.regex.Pattern.compile("(?m)^\\s*(?:(?:public|protected|private|static|final|abstract|strictfp)\\s+)*(?:class|interface|enum|record)\\s+"+quoted+"\\b");
            java.util.regex.Pattern fieldPattern=java.util.regex.Pattern.compile("(?m)^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*[^\\s=;()]+\\s+"+quoted+"\\s*(?:\\[\\s*\\])*\\s*(?:=|;)");
            for(int i=0;i<lines.length;i++)if(classPattern.matcher(lines[i]).find())return i+1;
            for(int i=0;i<lines.length;i++)if(fieldPattern.matcher(lines[i]).find())return i+1;
            for(int i=0;i<lines.length;i++)if(lines[i].contains(quoted))return i+1;
            return -1;
        }
        // Method declaration: "TYPE NAME(args) {" ends with '{'; a call site ends with ';'.
        java.util.regex.Pattern methodPattern=java.util.regex.Pattern.compile("(?m)^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract|native|strictfp)\\s+)*[^\\s=;()]+\\s+"+quoted+"\\s*\\(.*\\)\\s*\\{\\s*$");
        for(int i=0;i<lines.length;i++)if(methodPattern.matcher(lines[i].strip()).find())return i+1;
        for(int i=0;i<lines.length;i++)if(lines[i].contains(name+"("))return i+1;
        return -1;
    }
    private JComponent statusBar() {
        JPanel panel=new JPanel(new BorderLayout(10,0));panel.setBackground(new Color(0,108,185));panel.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));status.setForeground(Color.WHITE);position.setForeground(Color.WHITE);panel.add(status);
        JPanel east=new JPanel(new FlowLayout(FlowLayout.RIGHT,12,0));east.setOpaque(false);progress.setPreferredSize(new Dimension(95,13));progress.setVisible(false);east.add(progress);east.add(position);panel.add(east,BorderLayout.EAST);return panel;
    }
    private void welcome() {
        JPanel panel=new JPanel(new java.awt.GridBagLayout());panel.setBackground(Color.WHITE);
        JPanel content=new JPanel();content.setOpaque(false);content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS));
        JLabel name=new JLabel("JarStudio");name.setFont(new Font("Segoe UI",Font.PLAIN,38));name.setForeground(new Color(63,79,99));name.setAlignmentX(.5f);content.add(name);content.add(Box.createVerticalStrut(12));
        JLabel subtitle=new JLabel("Kéo thả JAR vào đây để đọc và sửa code");subtitle.setFont(Ui.FONT.deriveFont(17f));subtitle.setForeground(new Color(99,110,126));subtitle.setAlignmentX(.5f);content.add(subtitle);content.add(Box.createVerticalStrut(25));
        JButton open=Ui.button("Mở tệp JAR…","open",this::chooseOpen);open.setAlignmentX(.5f);open.setMaximumSize(new Dimension(190,36));content.add(open);content.add(Box.createVerticalStrut(24));
        JLabel steps=new JLabel("1. Mở JAR     →     2. Sửa Java / Hằng số     →     3. Lưu JAR");steps.setForeground(new Color(123,132,143));steps.setAlignmentX(.5f);content.add(steps);panel.add(content);editors.addTab("Bắt đầu",panel);
    }
    public List<JarSession> sessions(){return List.copyOf(sessions.values());}
    public List<EditorTab> documents(){return List.copyOf(documents);}
    public EditorTab active(){return editors.getSelectedComponent() instanceof EditorTab tab?tab:null;}
    public JarSession selectedSession() {
        EditorTab current=active();if(current!=null)return current.session;
        TreePath path=explorer.getSelectionPath();if(path!=null)for(Object component:path.getPath()){Object value=((DefaultMutableTreeNode)component).getUserObject();if(value instanceof JarSession session)return session;}
        return sessions.values().stream().findFirst().orElse(null);
    }
    private Item selectedItem(){Object node=explorer.getLastSelectedPathComponent();return node instanceof DefaultMutableTreeNode tree&&tree.getUserObject() instanceof Item item?item:null;}
    private void chooseOpen(){JFileChooser picker=new JFileChooser(preferences.get("lastFolder",System.getProperty("user.home")));picker.setMultiSelectionEnabled(true);picker.setFileFilter(new FileNameExtensionFilter("Java archives (*.jar, *.zip)","jar","zip"));if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION){preferences.put("lastFolder",picker.getCurrentDirectory().toString());for(java.io.File file:picker.getSelectedFiles())openPath(file.toPath(),true);}}
    public void openFile() {
        JFileChooser picker=new JFileChooser(preferences.get("lastFileFolder",preferences.get("lastFolder",System.getProperty("user.home"))));
        picker.setFileFilter(new FileNameExtensionFilter("Mọi tệp","*"));
        if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)for(java.io.File file:picker.getSelectedFiles())openFile(file.toPath());
    }
    public void openFile(Path file) {
        Path path=file.toAbsolutePath().normalize();
        if(Files.isDirectory(path))return;
        if(!Files.isRegularFile(path)){showError(new IllegalArgumentException("Không mở được tệp (có thể đã bị xóa hoặc không tồn tại): "+path));return;}
        long size;
        try {size=Files.size(path);}
        catch(java.io.IOException error) {showError(error);return;}
        if(size>32L*1024*1024){showError(new IllegalArgumentException("Tệp lớn hơn 32 MB — quá nặng để mở trong editor. Hãy dùng Thêm tệp vào JAR hoặc Thay tệp."));return;}
        recentFiles.remove(path);recentFiles.add(0,path);if(recentFiles.size()>20)recentFiles.remove(recentFiles.size()-1);
        refreshRecentMenus();
        preferences.put("lastFileFolder",path.getParent().toString());
        job("Đang mở "+path.getFileName()+"…",()->{
            byte[] bytes=Files.readAllBytes(path);
            ClassTools.Info info=null;
            if(path.toString().toLowerCase().endsWith(".class"))info=ClassTools.info(bytes);
            String source=null,backend="",notes="";
            if(info!=null) {
                try{DecompilerService.Decompiled result=decompiler.decompileFile(path,(DecompilerService.Engine)engine.getSelectedItem());source=result.source();backend=result.engine();}
                catch(Exception error){notes="Không dịch được class này: "+error.getMessage();}
            }
            boolean text=info==null&&isLikelyText(path,bytes);
            if(info==null&&!text&&bytes.length>0)throw new IllegalArgumentException("Tệp nhị phân, không phải code/tệp văn bản: "+path);
            return new EditorTab.Loaded(bytes,info,source,backend,notes);
        },loaded->{
            String label=path.toString();
            for(EditorTab tab:documents)if(label.equals(tab.entry)){editors.setSelectedComponent(tab);return;}
            EditorTab tab=new EditorTab(null,path.toString(),loaded,path,loaded.bytes(),this);
            tab.theme(dark);tab.editor.addCaretListener(event->updatePosition());documents.add(tab);editors.addTab(path.getFileName().toString(),tab);tabHeader(tab);editors.setSelectedComponent(tab);
            if(!loaded.note().isBlank())log(loaded.note());
            log("Đã mở tệp "+path);updateActions();
        });
    }
    private static boolean isLikelyText(Path file,byte[] bytes) {
        String name=file.toString().toLowerCase();
        if(name.matches(".*\\.(txt|java|xml|json|properties|md|csv|html|css|js|yml|yaml|conf|ini|fxml|svg|pom|gradle|cmd|ps1|sh)$"))return true;
        if(bytes.length>4*1024*1024)return false;
        for(int i=0;i<Math.min(bytes.length,8192);i++)if(bytes[i]==0)return false;
        return true;
    }
    private void saveActive() {
        EditorTab tab=active();
        if(tab==null)return;
        if(tab.fileExternal()) {
            // Save a local file on the EDT: it may show a modal (external change)
            // and updates tab title/status. A background job here could deadlock.
            try {tab.saveFile();status.setText("Đã lưu "+tab.file.getFileName());}
            catch(Exception error) {showError(error);}
            return;
        }
        saveSelected(false);
    }
    private void findInEditor(){
        EditorTab tab=active();if(tab==null)return;
        String text=tab.editor.getSelectedText();
        if(text==null||text.isEmpty())text=preferences.get("findText","");
        if(text.isEmpty()){showError(new IllegalArgumentException("Nhập cụm từ cần tìm."));return;}
        findContext.setSearchFor(text);findContext.setSearchForward(true);findContext.setSearchWrap(true);
        var result=org.fife.ui.rtextarea.SearchEngine.find(tab.editor,findContext);
        if(result==null||!result.wasFound()) {
            JOptionPane.showMessageDialog(this,"Không tìm thấy \""+text+"\".","Tìm",JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        preferences.put("findText",text);tab.editor.select(result.getMatchRange().getStartOffset(),result.getMatchRange().getEndOffset());tab.editor.requestFocusInWindow();
    }
    private void replaceInEditor(){
        EditorTab tab=active();if(tab==null)return;
        String find=preferences.get("findText","");String replace=preferences.get("replaceText","");
        Object[] fields=new Object[]{new JLabel("Tìm:"),new JTextField(find,18),new JLabel("Thay:"),new JTextField(replace,18)};
        int choice=JOptionPane.showOptionDialog(this,new Object[]{fields},"Tìm và thay (Ctrl+H)",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE,null,null,null);
        if(choice!=JOptionPane.OK_OPTION)return;
        String findText=((JTextField)fields[1]).getText();String replaceText=((JTextField)fields[3]).getText();
        if(findText.isEmpty()){showError(new IllegalArgumentException("Nhập cụm từ cần tìm."));return;}
        preferences.put("findText",findText);preferences.put("replaceText",replaceText);
        findContext.setSearchFor(findText);findContext.setReplaceWith(replaceText);findContext.setSearchForward(true);
        int replaced=org.fife.ui.rtextarea.SearchEngine.replaceAll(tab.editor,findContext).getCount();
        status.setText("Đã thay "+replaced+" vị trí");
    }
    private void nextFind(){
        EditorTab tab=active();if(tab==null)return;
        String text=tab.editor.getSelectedText();
        if(text==null||text.isEmpty())text=preferences.get("findText","");
        if(text.isEmpty()){status.setText("Không có cụm từ để tìm tiếp.");return;}
        findContext.setSearchFor(text);findContext.setSearchForward(true);findContext.setSearchWrap(true);
        var result=org.fife.ui.rtextarea.SearchEngine.find(tab.editor,findContext);
        if(result!=null&&result.wasFound()){tab.editor.select(result.getMatchRange().getStartOffset(),result.getMatchRange().getEndOffset());tab.editor.requestFocusInWindow();}
        else status.setText("Không còn kết quả \""+text+"\".");
    }
    private void gotoClass(){
        String query=JOptionPane.showInputDialog(this,"Tên class (đủ package, vd: com.example.Main):",active()==null?"":activeTabSimple());
        if(query==null||query.isBlank())return;
        gotoClass(query);
    }
    public void gotoClass(String query) {
        String name=query.strip().replace('.','/');
        List<JarSession> opened=sessions();
        job("Đang tìm class "+name+"…",()->new SearchService().search(opened,name.replace('/','.'),200),hits->{
            String exact=null;
            for(var hit:hits)if(hit.entry().equals(name+".class")){exact=name+".class";break;}
            if(exact!=null){JarSession session=hits.get(0).session();openEntry(session,exact,null);}
            else if(!hits.isEmpty())showHits(hits);
            else showError(new IllegalArgumentException("Không tìm thấy class "+name+". Mở thêm JAR phụ thuộc hoặc nhập package đầy đủ."));
        });
    }
    private String activeTabSimple(){var tab=active();if(tab==null)return "";String n=tab.info!=null?tab.info.internalName().replace('/','.'):tab.entry.substring(tab.entry.lastIndexOf('/')+1);return n.endsWith(".class")?n.substring(0,n.length()-6):n;}
    private void toggleFolds(){
        var tab=active();if(tab==null)return;
        var manager=tab.editor.getFoldManager();
        boolean allCollapsed=true;
        for(int i=0;i<manager.getFoldCount();i++)if(!manager.getFold(i).isCollapsed()){allCollapsed=false;break;}
        boolean collapse=!allCollapsed;
        for(int i=0;i<manager.getFoldCount();i++)manager.getFold(i).setCollapsed(collapse);
        status.setText(collapse?"Đã gom toàn bộ code.":"Đã mở toàn bộ code.");
    }
    private void toggleComment(){
        var tab=active();if(tab==null||!tab.canEdit())return;
        try{
            int start=tab.editor.getSelectionStart(),end=tab.editor.getSelectionEnd();
            if(start==end){
                int line=tab.editor.getLineOfOffset(end);
                int lineStart=tab.editor.getLineStartOffset(line);
                String text=tab.editor.getText(lineStart,tab.editor.getLineEndOffset(line)-lineStart);
                if(text.strip().startsWith("//")){int at=text.indexOf("//");tab.editor.replaceRange(text.substring(0,at),lineStart,lineStart+at);}
                else tab.editor.replaceRange("// "+text,lineStart,lineStart+text.length());
                return;
            }
            int firstLine=tab.editor.getLineOfOffset(Math.min(start,end)),lastLine=tab.editor.getLineOfOffset(Math.max(start,end));
            boolean allCommented=true;
            for(int l=firstLine;l<=lastLine;l++){
                int lineStart=tab.editor.getLineStartOffset(l);
                if(lineStart<start||lineStart>end)continue;
                String t=tab.editor.getText(lineStart,tab.editor.getLineEndOffset(l)-lineStart).strip();
                if(!t.startsWith("//")){allCommented=false;break;}
            }
            for(int l=lastLine;l>=firstLine;l--){
                int lineStart=tab.editor.getLineStartOffset(l);
                if(lineStart<start||lineStart>end)continue;
                String line=tab.editor.getText(lineStart,tab.editor.getLineEndOffset(l)-lineStart);
                if(allCommented){int at=line.indexOf("//");if(at>=0)tab.editor.replaceRange(line.substring(0,at),lineStart,lineStart+at);}
                else if(!line.strip().startsWith("//"))tab.editor.replaceRange("// "+line,lineStart,lineStart+line.length());
            }
        }catch(Exception error){showError(error);}
    }
    public void compileAll(){
        List<Edit> pending=new ArrayList<>();
        for(EditorTab tab:documents)if(tab.session!=null&&tab.isClass()&&tab.dirty()){
            if(tab.sourceDirty()&&!tab.pendingConstants().isEmpty()){showError(new IllegalStateException("Không trộn sửa Java và Hằng số trong cùng class: "+tab.entry));continue;}
            pending.add(new Edit(tab,tab.source(),tab.isClass()?null:tab.resourceBytes(),tab.pendingConstants()));
        }
        if(pending.isEmpty()){showError(new IllegalStateException("Không có class nào trong JAR đang chờ biên dịch. Ctrl+S để lưu tài nguyên; F6 để biên dịch class đang mở."));return;}
        job("Đang biên dịch "+pending.size()+" class…",()->apply(pending),applied->{
            accepted(applied);
            int total=applied.outputs().values().stream().mapToInt(Map::size).sum();
            status.setText("Đã biên dịch "+total+" class. Ctrl+S để lưu JAR.");log("Biên dịch hàng loạt: "+total+" class.");
        });
    }
    private record RunResult(Applied applied,String output){}
    private void runMain(){
        JarSession session=selectedSession();
        if(session==null){showError(new IllegalStateException("Mở JAR trước khi chạy class."));return;}
        String guess=session.mainClass();
        EditorTab tab=active();
        if((guess==null||guess.isBlank())&&tab!=null&&tab.session==session&&tab.info!=null)guess=tab.info.binaryName();
        String main=JOptionPane.showInputDialog(this,"Class có public static void main(String[] args):",guess==null?"":guess);
        if(main==null||main.isBlank())return;
        runMain(main);
    }
    public CompletableFuture<String> runMain(String main) {
        JarSession session=selectedSession();
        if(session==null)return CompletableFuture.failedFuture(new IllegalStateException("Mở JAR trước khi chạy class."));
        List<Edit> pending;
        try {pending=edits(session);}
        catch(Exception error) {return CompletableFuture.failedFuture(error);}
        return job("Đang biên dịch và chạy "+main+"…",()->{
            Applied applied=apply(pending);
            JarSession fork=new JarSession(session.input());
            var snapshot=session.snapshot();fork.stageAll(snapshot.changed());for(String name:snapshot.deleted())fork.delete(name);
            Map<String,byte[]> produced=applied.outputs().get(session);if(produced!=null)fork.stageAll(produced);
            Path jar=ArchiveWriter.write(fork.snapshot(),AppPaths.temporary("run-").resolve("run-target.jar"));
            List<Path> cp=new ArrayList<>();cp.add(jar);cp.addAll(session.libraries());
            var result=ProcessRunner.run(List.of(AppPaths.javaExecutable().toString(),"-cp",String.join(java.io.File.pathSeparator,cp.stream().map(Path::toString).toList()),main),null,java.time.Duration.ofSeconds(60));
            return new RunResult(applied,result.output());
        },runResult->{
            accepted(runResult.applied());
            log("---- đầu ra "+main+" ----");
            log(runResult.output()==null||runResult.output().isBlank()?"(không có đầu ra)":runResult.output().strip());
            tools.setSelectedIndex(0);status.setText("Đã chạy "+main);
        }).thenApply(RunResult::output);
    }
    public CompletableFuture<JarSession> openPath(Path path,boolean autoOpen) {
        Path normalized=path.toAbsolutePath().normalize();JarSession existing=sessions.get(normalized);
        if(existing!=null)return CompletableFuture.completedFuture(existing);
        if(!Files.isRegularFile(normalized)||(!normalized.toString().toLowerCase().endsWith(".jar")&&!normalized.toString().toLowerCase().endsWith(".zip"))) {
            showError(new IllegalArgumentException("Chọn tệp .jar hoặc .zip chứa Java. Bản EXE là ứng dụng Windows của JarStudio; không phải trình chỉnh sửa .NET."));return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported file"));
        }
        return job("Đang mở "+normalized.getFileName()+"…",()->new JarSession(normalized),session->{
            sessions.put(session.input(),session);
            recentJars.remove(session.input());recentJars.add(0,session.input());if(recentJars.size()>20)recentJars.remove(recentJars.size()-1);refreshRecentMenus();
            rebuildTree();log("Đã mở "+session.input()+" — "+session.entries().size()+" tệp");status.setText("Đã mở "+session.displayName());
            if(autoOpen){String main=session.mainClass();String first=main!=null&&session.entries().stream().anyMatch(entry->entry.name().equals(main.replace('.','/')+".class"))?main.replace('.','/')+".class":session.entries().stream().filter(JarSession.Entry::isClass).map(JarSession.Entry::name).findFirst().orElse("META-INF/MANIFEST.MF");openEntry(session,first,null);}
        });
    }
    private void rebuildTree() {
        String query=filter.getText().strip().toLowerCase();root.removeAllChildren();
        for(JarSession session:sessions.values()) {
            DefaultMutableTreeNode jar=new DefaultMutableTreeNode(session);Map<String,DefaultMutableTreeNode> directories=new LinkedHashMap<>();directories.put("",jar);
            for(JarSession.Entry entry:session.entries()) {
                if(entry.directory()||(!query.isBlank()&&!entry.name().toLowerCase().contains(query)))continue;
                String[] parts=entry.name().split("/");String parent="";
                for(int i=0;i<parts.length-1;i++) {
                    String next=parent+parts[i]+"/";
                    if(!directories.containsKey(next)){DefaultMutableTreeNode folder=new DefaultMutableTreeNode(new Folder(session,next,parts[i]));directories.get(parent).add(folder);directories.put(next,folder);}parent=next;
                }
                DefaultMutableTreeNode file=new DefaultMutableTreeNode(new Item(session,entry.name(),null));if(entry.isClass())file.add(new DefaultMutableTreeNode("…"));directories.get(parent).add(file);
            }
            root.add(jar);
        }
        treeModel.reload();for(int row=0;row<explorer.getRowCount();row++)if(!query.isBlank()||explorer.getPathForRow(row).getPathCount()<=2)explorer.expandRow(row);updateActions();
    }
    private void loadMembers(DefaultMutableTreeNode node,Item item) {
        job("Đang đọc thành viên class…",()->ClassTools.info(item.session().read(item.entry())),info->{node.removeAllChildren();for(ClassTools.Member member:info.members())node.add(new DefaultMutableTreeNode(new Item(item.session(),item.entry(),member)));treeModel.nodeStructureChanged(node);});
    }
    public CompletableFuture<EditorTab> openEntry(JarSession session,String name,String member) {
        if(name.endsWith(".class")&&name.contains("$"))return job("Đang tìm Java source của class lồng…",()->ClassTools.compilationEntry(session,name),unit->{}).thenCompose(unit->{if(!unit.equals(name))log("Sửa "+name+" qua source class ngoài "+unit+"; Bytecode/Hằng số class riêng vẫn có trong menu chuột phải.");return openRawEntry(session,unit,member);});
        return openRawEntry(session,name,member);
    }
    private CompletableFuture<EditorTab> openRawEntry(JarSession session,String name,String member) {
        for(EditorTab tab:documents)if(tab.session==session&&tab.entry.equals(name)){editors.setSelectedComponent(tab);tab.jump(member);return CompletableFuture.completedFuture(tab);}
        return job("Đang dịch ngược "+name+"…",()->{
            byte[] bytes=session.read(name);ClassTools.Info info=name.endsWith(".class")?ClassTools.info(bytes):null;String source=null,backend="",notes="";
            if(info!=null)try{DecompilerService.Decompiled result=decompiler.decompile(session,name,(DecompilerService.Engine)engine.getSelectedItem());source=result.source();backend=result.engine();notes=result.notes();}catch(Exception error){notes=error.getMessage();}
            return new EditorTab.Loaded(bytes,info,source,backend,notes);
        },loaded->{
            if(!sessions.containsValue(session))return;
            EditorTab tab=new EditorTab(session,name,loaded,this);tab.theme(dark);tab.editor.addCaretListener(event->updatePosition());documents.add(tab);editors.addTab(tab.title(),tab);tabHeader(tab);editors.setSelectedComponent(tab);tab.jump(member);
            if(!loaded.note().isBlank())log(loaded.note());updateActions();
        }).thenApply(loaded->documents.stream().filter(tab->tab.session==session&&tab.entry.equals(name)).findFirst().orElseThrow());
    }
    private void tabHeader(EditorTab tab) {
        JPanel header=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0));header.setOpaque(false);JLabel label=new JLabel(tab.title(),Ui.icon(tab.isClass()?"class":"file"),SwingConstants.LEFT);header.add(label);
        JButton close=new JButton(Ui.icon("close"));close.setBorder(BorderFactory.createEmptyBorder());close.setContentAreaFilled(false);close.setFocusable(false);close.setToolTipText("Đóng tab");close.addActionListener(event->closeTab(tab));header.add(close);editors.setTabComponentAt(editors.indexOfComponent(tab),header);
    }
    private void closeTab(EditorTab tab){if(tab.dirty()&&JOptionPane.showConfirmDialog(this,"Tab này có thay đổi chưa biên dịch/lưu. Đóng và bỏ thay đổi?","Đóng tab",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;documents.remove(tab);editors.remove(tab);updateActions();}
    public void changed(EditorTab tab){int index=editors.indexOfComponent(tab);if(index>=0){editors.setTitleAt(index,tab.title());tabHeader(tab);}explorer.repaint();updateActions();}
    public void editConstant(EditorTab tab,ClassTools.Constant constant) {
        if(busy>0)return;
        if(tab.sourceDirty()){showError(new IllegalStateException("Java đang có thay đổi. Biên dịch hoặc hoàn tác Java trước khi dùng sửa Hằng số."));return;}
        JTextArea value=new JTextArea(tab.pendingConstants().getOrDefault(constant.key(),constant.text()),6,60);value.setFont(Ui.CODE);
        JPanel panel=new JPanel(new BorderLayout(4,7));panel.add(new JLabel(constant.type()+" — "+constant.location()),BorderLayout.NORTH);panel.add(new JScrollPane(value));
        if(JOptionPane.showConfirmDialog(this,panel,"Sửa hằng số trực tiếp",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){tab.editedConstant(constant,value.getText());status.setText("Đã sửa hằng số trong tab. F6 áp dụng, Ctrl+S lưu JAR.");}
    }
    private List<Edit> edits(JarSession session) {
        List<Edit> result=new ArrayList<>();for(EditorTab tab:documents)if(tab.session==session&&tab.dirty()) {
            if(tab.sourceDirty()&&!tab.pendingConstants().isEmpty())throw new IllegalStateException("Không trộn sửa Java và Hằng số trước khi biên dịch. Hãy hoàn tác một trong hai phần.");
            result.add(new Edit(tab,tab.source(),tab.isClass()?null:tab.resourceBytes(),tab.pendingConstants()));
        }return result;
    }
    private Applied apply(List<Edit> edits) throws Exception {
        Map<JarSession,Map<String,byte[]>> outputs=new LinkedHashMap<>();Map<EditorTab,String> sources=new LinkedHashMap<>();Map<JarSession,List<Edit>> grouped=new LinkedHashMap<>();
        for(Edit edit:edits)grouped.computeIfAbsent(edit.tab().session,key->new ArrayList<>()).add(edit);
        for(var group:grouped.entrySet()) {
            JarSession session=group.getKey();JarSession fork=new JarSession(session.input());JarSession.Snapshot snapshot=session.snapshot();fork.stageAll(snapshot.changed());for(String deleted:snapshot.deleted())fork.delete(deleted);for(Path library:session.libraries())fork.addLibrary(library);
            LinkedHashMap<String,String> javaSources=new LinkedHashMap<>();LinkedHashMap<String,byte[]> changed=new LinkedHashMap<>();
            for(Edit edit:group.getValue()) {
                EditorTab tab=edit.tab();
                if(tab.isClass()&&edit.constants().isEmpty())javaSources.put(tab.entry,edit.source());
                else if(!tab.isClass()){changed.put(tab.entry,edit.resource());fork.stage(tab.entry,edit.resource());sources.put(tab,edit.source());}
            }
            if(!javaSources.isEmpty()) {
                List<Path> libraries=new ArrayList<>();for(JarSession other:sessions.values())if(other!=session)libraries.add(other.input());
                CompilerService.Compiled compiled=compiler.compileBatch(fork,javaSources,libraries);changed.putAll(compiled.classes());fork.stageAll(compiled.classes());
            }
            for(Edit edit:group.getValue()) {
                EditorTab tab=edit.tab();
                if(tab.isClass()&&!edit.constants().isEmpty()) {
                    if(javaSources.size()>0&&changed.containsKey(tab.entry))throw new IllegalStateException("Class có hằng số đang sửa cũng được tạo bởi lần biên dịch Java này. Lưu riêng từng phần để tránh ghi đè thay đổi.");
                    byte[] bytes=ClassTools.replaceConstants(fork.read(tab.entry),edit.constants());changed.put(tab.entry,bytes);fork.stage(tab.entry,bytes);
                    try{sources.put(tab,decompiler.decompile(fork,tab.entry,DecompilerService.Engine.AUTO).source());}catch(Exception ignored){sources.put(tab,edit.source());}
                } else if(tab.isClass())sources.put(tab,edit.source());
            }
            outputs.put(session,changed);
        }
        return new Applied(outputs,sources);
    }
    private void accepted(Applied applied) {
        applied.outputs().forEach(JarSession::stageAll);
        applied.sources().forEach((tab,text)->{try{if(!tab.pendingConstants().isEmpty())tab.replaceSource(text,tab.session.read(tab.entry));else tab.compiled(text,tab.isClass()?tab.session.read(tab.entry):new byte[0]);}catch(Exception error){log(error.toString());}});
        rebuildTree();
    }
    public CompletableFuture<Void> compileTab(EditorTab tab) {
        if(tab==null)return CompletableFuture.completedFuture(null);
        if(tab.fileExternal()){showError(new IllegalStateException("Tab tệp ngoài không biên dịch trong JAR — dùng Ctrl+S để lưu tệp."));return CompletableFuture.completedFuture(null);}
        if(tab.sourceDirty()&&!tab.pendingConstants().isEmpty()){IllegalStateException error=new IllegalStateException("Không trộn sửa Java và Hằng số trước khi biên dịch. Hoàn tác một phần trước.");showError(error);return CompletableFuture.failedFuture(error);}
        errorDocument=tab;errorModel.setRowCount(0);List<Edit> pending=List.of(new Edit(tab,tab.source(),tab.isClass()?null:tab.resourceBytes(),tab.pendingConstants()));
        return job("Đang biên dịch "+tab.title()+"…",()->apply(pending),result->{accepted(result);tab.clearErrorMarkers();status.setText("Biên dịch thành công. Ctrl+S để lưu JAR.");log("Biên dịch thành công: "+tab.entry);}).whenComplete((result,error)->{
            if(error!=null){while(error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null)error=error.getCause();if(error instanceof CompilerService.CompileFailure failure&&tab.isClass())tab.setErrors(failure.diagnostics());}
        }).thenApply(result->null);
    }
    private void compileSelected(){EditorTab tab=active();if(tab!=null)compileTab(tab);}
    private void saveSelected(boolean choose) {
        EditorTab current=active();
        if(current!=null&&current.fileExternal()){saveActive();return;}
        JarSession session=selectedSession();if(session==null)return;
        Path destination=choose?null:savedOutputs.get(session);
        if(destination==null) {
            JFileChooser picker=new JFileChooser(session.input().getParent().toFile());String name=session.displayName().replaceFirst("(?i)\\.(jar|zip)$","");picker.setSelectedFile(new java.io.File(name+"-edited.jar"));picker.setFileFilter(new FileNameExtensionFilter("Java archive (*.jar)","jar"));
            if(picker.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;destination=picker.getSelectedFile().toPath();if(!destination.toString().toLowerCase().endsWith(".jar"))destination=Path.of(destination+".jar");
            if(Files.exists(destination)&&JOptionPane.showConfirmDialog(this,"Tệp đã tồn tại. Ghi đè tệp này?\n"+destination,"Lưu JAR",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        }
        saveTo(session,destination);
    }
    public CompletableFuture<Path> saveTo(JarSession session,Path destination) {
        List<Edit> pending;try{pending=edits(session);}catch(Exception error){showError(error);return CompletableFuture.failedFuture(error);}
        return job("Đang biên dịch và lưu JAR…",()->{
            Applied applied=apply(pending);JarSession fork=new JarSession(session.input());var snapshot=session.snapshot();fork.stageAll(snapshot.changed());for(String deleted:snapshot.deleted())fork.delete(deleted);
            Map<String,byte[]> produced=applied.outputs().get(session);if(produced!=null)fork.stageAll(produced);
            ArchiveWriter.write(fork.snapshot(),destination);return applied;
        },applied->{
            accepted(applied);Path old=session.input();try{session.acceptedSave(destination);}catch(Exception error){showError(error);return;}
            sessions.remove(old);sessions.put(session.input(),session);savedOutputs.put(session,session.input());for(EditorTab tab:documents)if(tab.session==session)tab.markSaved();
            rebuildTree();log("Đã lưu và kiểm tra CRC: "+destination);status.setText("Đã lưu "+destination.getFileName());setTitle("JarStudio — "+destination.getFileName());
        }).thenApply(result->destination);
    }
    private void reloadSelected(){EditorTab tab=active();if(tab==null||!tab.isClass())return;if(tab.dirty()&&JOptionPane.showConfirmDialog(this,"Bỏ thay đổi trong tab để dịch lại class?","Tải lại",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;job("Đang tải lại Java…",()->decompiler.decompile(tab.session,tab.entry,(DecompilerService.Engine)engine.getSelectedItem()),result->{try{tab.replaceSource(result.source(),tab.session.read(tab.entry));}catch(Exception error){showError(error);}});}
    private void replaceFile() {
        Item item=selectedItem();EditorTab tab=active();JarSession session=item!=null?item.session():tab==null?null:tab.session;String entry=item!=null?item.entry():tab==null?null:tab.entry;if(session==null||entry==null)return;
        JFileChooser picker=new JFileChooser();if(picker.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=picker.getSelectedFile().toPath();
        job("Đang thay tệp…",()->{if(Files.size(file)>64L*1024*1024)throw new IllegalArgumentException("Tệp thay thế lớn hơn 64 MB.");byte[] bytes=Files.readAllBytes(file);if(entry.endsWith(".class")&&!ClassTools.info(bytes).internalName().equals(ClassTools.info(session.read(entry)).internalName()))throw new IllegalArgumentException("Class thay thế phải có cùng package/tên class.");return bytes;},bytes->{session.stage(entry,bytes);removeLoaded(session,entry);rebuildTree();log("Đã thay "+entry+" trong workspace; Ctrl+S để lưu.");});
    }
    private void addFile() {
        JarSession session=selectedSession();if(session==null)return;JFileChooser picker=new JFileChooser();if(picker.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path file=picker.getSelectedFile().toPath();
        String name=JOptionPane.showInputDialog(this,"Đường dẫn bên trong JAR:",file.getFileName().toString());if(name==null)return;
        try{JarSession.validateEntry(name);if(session.entries().stream().anyMatch(entry->entry.name().equals(name))&&JOptionPane.showConfirmDialog(this,"Tệp đã tồn tại. Thay nội dung?","Thêm tệp",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;}catch(Exception error){showError(error);return;}
        job("Đang nhập tệp…",()->Files.readAllBytes(file),bytes->{session.stage(name,bytes);removeLoaded(session,name);rebuildTree();});
    }
    private void deleteSelected(){Item item=selectedItem();if(item==null)return;if(JOptionPane.showConfirmDialog(this,"Xóa khỏi JAR khi lưu?\n"+item.entry(),"Xóa tệp",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION){item.session().delete(item.entry());removeLoaded(item.session(),item.entry());rebuildTree();}}
    private void removeLoaded(JarSession session,String entry){for(EditorTab tab:new ArrayList<>(documents))if(tab.session==session&&tab.entry.equals(entry)){documents.remove(tab);editors.remove(tab);}}
    private void defaultReturn(){Item item=selectedItem();if(item==null||item.member()==null||!item.member().method())return;if(JOptionPane.showConfirmDialog(this,"Thay toàn bộ thân hàm bằng return 0/false/null (hoặc return cho void)?\n"+item.member().display(),"Thay thân hàm",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;job("Đang sửa bytecode hàm…",()->ClassTools.defaultReturn(item.session().read(item.entry()),item.member().name(),item.member().descriptor()),bytes->{item.session().stage(item.entry(),bytes);removeLoaded(item.session(),item.entry());rebuildTree();openEntry(item.session(),item.entry(),item.member().name());});}
    private void exportSelected(){EditorTab tab=active();if(tab==null)return;JFileChooser picker=new JFileChooser();String defaultName=tab.fileExternal()?tab.file.getFileName().toString():(tab.isClass()?tab.info.internalName().substring(tab.info.internalName().lastIndexOf('/')+1)+".java":tab.entry.substring(tab.entry.lastIndexOf('/')+1).replace('\\','/'));picker.setSelectedFile(new java.io.File(defaultName));if(picker.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;Path destination=picker.getSelectedFile().toPath();job("Đang xuất tệp…",()->{byte[] bytes=tab.fileExternal()?(tab.canEdit()?tab.resourceBytes():Files.readAllBytes(tab.file)):(tab.isClass()?tab.source().getBytes(java.nio.charset.StandardCharsets.UTF_8):tab.canEdit()?tab.resourceBytes():tab.session.read(tab.entry));Files.write(destination,bytes);return destination;},path->log("Đã xuất "+path));}
    private void libraries(){JarSession session=selectedSession();if(session==null){showError(new IllegalStateException("Mở JAR trước khi thêm thư viện."));return;}DefaultListModel<Path> items=new DefaultListModel<>();session.libraries().forEach(items::addElement);JList<Path> list=new JList<>(items);JPanel panel=new JPanel(new BorderLayout());JScrollPane scroll=new JScrollPane(list);scroll.setPreferredSize(new Dimension(650,220));panel.add(scroll);JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT));bar.add(Ui.button("Thêm JAR…","open",()->{JFileChooser picker=new JFileChooser();picker.setMultiSelectionEnabled(true);picker.setFileFilter(new FileNameExtensionFilter("JAR libraries","jar"));if(picker.showOpenDialog(this)==JFileChooser.APPROVE_OPTION)for(java.io.File file:picker.getSelectedFiles()){session.addLibrary(file.toPath());if(!items.contains(file.toPath()))items.addElement(file.toPath());}}));bar.add(Ui.button("Bỏ thư viện","close",()->{Path value=list.getSelectedValue();if(value!=null){session.removeLibrary(value);items.removeElement(value);}}));panel.add(bar,BorderLayout.SOUTH);JOptionPane.showMessageDialog(this,panel,"Thư viện phụ thuộc — tự nhận thư mục lib/libs",JOptionPane.PLAIN_MESSAGE);}
    private void searchAll(){String query=search.getText();List<JarSession> opened=sessions();job("Đang tìm trong JAR…",()->new SearchService().search(opened,query,5000),this::showHits);}
    private void analyzeSelected(){Item item=selectedItem();EditorTab tab=active();if(item==null&&tab!=null)item=new Item(tab.session,tab.entry,null);if(item==null||!item.entry().endsWith(".class"))return;Item target=item;List<JarSession> opened=sessions();job("Đang tìm nơi sử dụng…",()->new SearchService().references(opened,ClassTools.info(target.session().read(target.entry())).internalName(),target.member()),this::showHits);}
    private void showHits(List<SearchService.Hit> hits){searchHits=hits;resultModel.setRowCount(0);for(var hit:hits)resultModel.addRow(new Object[]{hit.kind(),hit.entry(),hit.member(),hit.detail()});tools.setSelectedIndex(1);status.setText(hits.size()+" kết quả");}
    private void goToLine(){EditorTab tab=active();if(tab==null)return;String line=JOptionPane.showInputDialog(this,"Đến dòng:",tab.line());if(line!=null)try{tab.jumpLine(Integer.parseInt(line));}catch(NumberFormatException error){showError(new IllegalArgumentException("Nhập số dòng hợp lệ."));}}
    private void fontSize(int difference){for(EditorTab tab:documents)tab.editor.setFont(tab.editor.getFont().deriveFont((float)Math.max(10,Math.min(32,tab.editor.getFont().getSize()+difference))));}
    public void breakpoint(EditorTab tab,int line){if(tab.info==null)return;try{debugger.toggle(DebugController.Breakpoint.line(tab.info.binaryName(),line));tab.toggleBreakpointIcon(line);refreshBreakpoints();log("Breakpoint "+tab.info.binaryName()+":"+line+" — dòng khớp chính xác sau khi biên dịch source đang xem.");}catch(Exception error){showError(error);}}
    public void fileSaved(Path file){log("Đã lưu tệp: "+file);status.setText("Đã lưu "+file.getFileName());}
    public void complete(EditorTab tab){showCompletion(tab);}
    private static final String[] KEYWORDS={"public","private","protected","static","final","void","return","new","if","else","for","while","do","try","catch","finally","throw","throws","class","interface","enum","extends","implements","import","package","this","super","null","true","false","int","long","short","byte","char","float","double","boolean","synchronized","abstract","native","volatile","transient","instanceof","break","continue","switch","case","default","assert","yield","var","record","sealed","permits"};
    private CompletionPopup completionPopup;
    private void showCompletion(EditorTab tab) {
        if(tab==null)return;
        String prefix=tab.caretWord();
        java.util.TreeSet<String> candidates=new java.util.TreeSet<>();
        for(String word:tab.source().split("\\b"))if(word.matches("[A-Za-z_$][A-Za-z0-9_$]{2,}")&&word.startsWith(prefix))candidates.add(word);
        if(tab.info!=null)for(ClassTools.Member member:tab.info.members())if(!member.name().equals("<init>")&&!member.name().equals("<clinit>")&&member.name().startsWith(prefix))candidates.add(member.name());
        for(String keyword:KEYWORDS)if(keyword.startsWith(prefix)&&keyword.length()>prefix.length())candidates.add(keyword);
        if(candidates.isEmpty()){status.setText("Không có gợi ý cho \""+prefix+"\"");return;}
        List<String> list=new ArrayList<>(candidates);list.sort(java.util.Comparator.naturalOrder());
        if(list.size()>200)list=new ArrayList<>(list.subList(0,200));
        if(completionPopup!=null){completionPopup.close();completionPopup=null;}
        try {completionPopup=new CompletionPopup(tab,list,caretScreenPoint(tab));}
        catch(Exception error){
            String chosen=JOptionPane.showInputDialog(this,"Tự hoàn thành cho \""+prefix+"\" (Ctrl+Space). Nhập để lọc, Enter để chọn:","");
            if(chosen!=null&&!chosen.isBlank()){String best=list.stream().filter(c->c.startsWith(chosen.trim())).findFirst().orElse(prefix);tab.completeWord(best);tab.editor.requestFocusInWindow();}
        }
    }
    private java.awt.Point caretScreenPoint(EditorTab tab) {
        java.awt.Point screen;
        try {
            screen=SwingUtilities.convertPoint(tab.editor,new java.awt.Point(0,0),null);
            int caret=tab.editor.getCaretPosition();
            int line=tab.editor.getLineOfOffset(caret);
            int height=tab.editor.getFontMetrics(tab.editor.getFont()).getHeight();
            screen=new java.awt.Point(screen.x+4,screen.y+Math.max(0,line)*height+2);
        } catch(Exception error) {
            try {screen=SwingUtilities.convertPoint(tab.editor,new java.awt.Point(0,0),null);}catch(Exception e2){screen=new java.awt.Point(160,160);}
        }
        return screen;
    }
    private static final class CompletionPopup {
        private final javax.swing.JWindow window;
        private final EditorTab tab;
        private final List<String> all;
        private final javax.swing.DefaultListModel<String> model=new javax.swing.DefaultListModel<>();
        private final javax.swing.JList<String> list=new javax.swing.JList<>(model);
        CompletionPopup(EditorTab tab,List<String> candidates,java.awt.Point screen) {
            this.tab=tab;this.all=candidates;
            window=new javax.swing.JWindow(SwingUtilities.getWindowAncestor(tab));
            window.setAlwaysOnTop(true);
            window.setBackground(new Color(249,251,253));
            javax.swing.JTextField filter=new javax.swing.JTextField(22);
            filter.setFont(Ui.FONT);
            filter.setBorder(BorderFactory.createEmptyBorder(2,3,2,3));
            list.setFont(Ui.FONT);
            list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
            javax.swing.JScrollPane scroll=new javax.swing.JScrollPane(list);
            scroll.setPreferredSize(new Dimension(340,180));
            scroll.setBorder(null);
            javax.swing.JPanel panel=new javax.swing.JPanel(new BorderLayout(0,2));
            panel.setBackground(window.getBackground());
            panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(150,160,175)),BorderFactory.createEmptyBorder(3,3,3,3)));
            panel.add(filter,BorderLayout.NORTH);
            panel.add(scroll,BorderLayout.CENTER);
            window.add(panel);
            window.pack();
            int x=screen.x,y=screen.y;
            int maxW=java.awt.Toolkit.getDefaultToolkit().getScreenSize().width;
            if(x+window.getWidth()>maxW-8)x=Math.max(8,maxW-window.getWidth()-8);
            window.setLocation(x,y);
            javax.swing.event.DocumentListener doc=new javax.swing.event.DocumentListener(){
                public void insertUpdate(javax.swing.event.DocumentEvent e){fill(filter.getText());}
                public void removeUpdate(javax.swing.event.DocumentEvent e){fill(filter.getText());}
                public void changedUpdate(javax.swing.event.DocumentEvent e){}
            };
            filter.getDocument().addDocumentListener(doc);
            list.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)pick(list.getSelectedValue());}});
            filter.addKeyListener(new java.awt.event.KeyAdapter(){public void keyPressed(java.awt.event.KeyEvent e){
                if(e.getKeyCode()==java.awt.event.KeyEvent.VK_ENTER){pick(list.getSelectedValue());}
                else if(e.getKeyCode()==java.awt.event.KeyEvent.VK_ESCAPE){close();}
                else if(e.getKeyCode()==java.awt.event.KeyEvent.VK_DOWN){int n=model.getSize();if(n>0)list.setSelectedIndex(Math.min(list.getSelectedIndex()+1,n-1));}
                else if(e.getKeyCode()==java.awt.event.KeyEvent.VK_UP){int n=model.getSize();if(n>0)list.setSelectedIndex(Math.max(list.getSelectedIndex()-1,0));}
            }});
            window.addWindowListener(new java.awt.event.WindowAdapter(){public void windowDeactivated(java.awt.event.WindowEvent e){close();}});
            window.setVisible(true);
            fill("");
            filter.requestFocusInWindow();
        }
        private void fill(String text) {
            model.clear();
            String low=text==null?"":text.toLowerCase();
            int added=0;
            for(String c:all){
                if(low.isEmpty()||c.toLowerCase().startsWith(low)){model.addElement(c);if(++added>=80)break;}
            }
            if(model.getSize()>0)list.setSelectedIndex(0);
        }
        private void pick(String value) {if(value==null)return;close();tab.completeWord(value);tab.editor.requestFocusInWindow();}
        private void close() {if(window.isShowing())window.dispose();}
    }
    private void toggleCurrentBreakpoint(){EditorTab tab=active();if(tab!=null)breakpoint(tab,tab.line());}
    private void methodBreakpoint(){Item item=selectedItem();if(item==null||item.member()==null||!item.member().method())return;Item selected=item;debugJob(()->{debugger.toggle(DebugController.Breakpoint.method(ClassTools.info(selected.session().read(selected.entry())).binaryName(),selected.member().name(),selected.member().descriptor()));SwingUtilities.invokeLater(this::refreshBreakpoints);return null;});}
    private void refreshBreakpoints(){breakpointModel.clear();debugger.breakpoints().forEach(breakpointModel::addElement);}
    private void startOrContinue() {
        if(debugger.state()==DebugController.State.PAUSED){debugger.resume();return;}
        if(debugger.state()!=DebugController.State.DISCONNECTED)return;JarSession session=selectedSession();if(session==null)return;
        List<Edit> pending;try{pending=edits(session);}catch(Exception error){showError(error);return;}
        boolean javaMe=session.isJavaMe();String main=session.mainClass();String args="";
        if(!javaMe&&(main==null||main.isBlank())){main=JOptionPane.showInputDialog(this,"Class có public static void main(String[] args):");if(main==null||main.isBlank())return;}
        String selectedMain=main;
        job("Đang chuẩn bị phiên debug…",()->{
            Applied applied=apply(pending);JarSession fork=new JarSession(session.input());var original=session.snapshot();fork.stageAll(original.changed());for(String name:original.deleted())fork.delete(name);Map<String,byte[]> changed=applied.outputs().get(session);if(changed!=null)fork.stageAll(changed);
            Path temporary=AppPaths.temporary("debug-");Path jar=ArchiveWriter.write(fork.snapshot(),temporary.resolve("debug-target.jar"));
            List<Path> cp=new ArrayList<>();String target,arguments;
            if(javaMe){cp.add(AppPaths.tool("midlet-launcher.jar"));cp.add(AppPaths.tool("microemulator.jar"));target="dev.jarstudio.tooling.MidletLauncher";arguments="--rms memory "+DebugController.quote(jar.toString());}
            else{cp.add(jar);cp.addAll(session.libraries());target=selectedMain;arguments="";}
            debugger.launch(target,arguments,cp);return applied;
        },this::accepted);
    }
    private void attach(){JTextField host=new JTextField("127.0.0.1"),port=new JTextField("5005");JPanel panel=new JPanel(new GridLayout(0,2,7,7));panel.add(new JLabel("Host:"));panel.add(host);panel.add(new JLabel("Port:"));panel.add(port);if(JOptionPane.showConfirmDialog(this,panel,"Gắn vào JVM có JDWP",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)debugJob(()->{debugger.attach(host.getText().strip(),Integer.parseInt(port.getText().strip()));return null;});}
    private void hotSwap(){EditorTab tab=active();if(tab==null||!tab.isClass())return;compileTab(tab).thenRun(()->debugJob(()->{Map<String,byte[]> changes=tab.session.snapshot().changed();int count=debugger.hotSwap(changes);SwingUtilities.invokeLater(()->log("Đã áp dụng code vào "+count+" class đang chạy. HotSwap JVM thường chỉ cho sửa thân hàm."));return null;}));}
    private void debugPaused(DebugController.Pause pause){changingStack=true;try{stackModel.clear();pause.frames().forEach(stackModel::addElement);if(!pause.frames().isEmpty())stack.setSelectedIndex(0);}finally{changingStack=false;}frameIndex=0;showVariables(pause.variables());refreshWatches();refreshThreads();tools.setSelectedIndex(3);if(!pause.frames().isEmpty()){var frame=pause.frames().get(0);String entry=frame.owner().replace('.','/')+".class";for(JarSession session:sessions.values())if(session.entries().stream().anyMatch(value->value.name().equals(entry))){openEntry(session,entry,frame.method()).thenAccept(tab->{if(frame.line()>0&&debugger.state()==DebugController.State.PAUSED)tab.showExecutionLine(frame.line());});break;}}updateActions();}
    private DefaultMutableTreeNode variableNode(DebugController.Variable value){DefaultMutableTreeNode node=new DefaultMutableTreeNode(value);if(value.value() instanceof com.sun.jdi.ObjectReference&&!(value.value() instanceof com.sun.jdi.StringReference))node.add(new DefaultMutableTreeNode("…"));return node;}
    private void showVariables(List<DebugController.Variable> variables){localsRoot.removeAllChildren();variables.forEach(value->localsRoot.add(variableNode(value)));localsModel.reload();}
    private void refreshThreads(){debugJob(()->{var threads=debugger.threads();SwingUtilities.invokeLater(()->{threadModel.setRowCount(0);for(var thread:threads)threadModel.addRow(new Object[]{thread.id(),(thread.current()?"▶ ":"")+thread.name(),thread.status(),thread.suspended()});});return null;});}
    private void editVariable(){Object selected=locals.getLastSelectedPathComponent();if(!(selected instanceof DefaultMutableTreeNode node)||!(node.getUserObject() instanceof DebugController.Variable value))return;String initial=value.value() instanceof com.sun.jdi.StringReference string?string.value():value.display();String text=JOptionPane.showInputDialog(this,"Giá trị mới cho "+value.name()+" ("+value.type()+"):",initial);if(text!=null)debugJob(()->{debugger.set(value,text);var pause=debugger.inspect(frameIndex,"Set variable");SwingUtilities.invokeLater(()->showVariables(pause.variables()));return null;});}
    private void refreshWatches(){if(debugger.state()!=DebugController.State.PAUSED)return;List<String> expressions=new ArrayList<>();for(int row=0;row<watchModel.getRowCount();row++)expressions.add(watchModel.getValueAt(row,0).toString());debugJob(()->{List<String> values=new ArrayList<>();for(String expression:expressions)try{values.add(debugger.watch(expression,frameIndex));}catch(Exception error){values.add(error.getMessage());}SwingUtilities.invokeLater(()->{for(int row=0;row<Math.min(values.size(),watchModel.getRowCount());row++)watchModel.setValueAt(values.get(row),row,1);});return null;});}
    private <T> void debugJob(Callable<T> action){Thread thread=new Thread(()->{try{action.call();}catch(Exception error){SwingUtilities.invokeLater(()->showError(error));}},"jarstudio-debug-action");thread.setDaemon(true);thread.start();}
    private void closeSession(){if(busy>0){status.setText("Chờ tác vụ hiện tại hoàn tất trước khi đóng JAR.");return;}JarSession session=selectedSession();if(session==null)return;if((session.dirty()||documents.stream().anyMatch(tab->tab.session==session&&tab.dirty()))&&JOptionPane.showConfirmDialog(this,"JAR có thay đổi chưa lưu. Đóng và bỏ các thay đổi?","Đóng JAR",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;for(EditorTab tab:new ArrayList<>(documents))if(tab.session==session){documents.remove(tab);editors.remove(tab);}sessions.values().removeIf(value->value==session);savedOutputs.remove(session);rebuildTree();}
    private void closeWindow(){if(busy>0){status.setText("Chờ tác vụ hiện tại hoàn tất trước khi thoát.");return;}if((sessions.values().stream().anyMatch(JarSession::dirty)||documents.stream().anyMatch(EditorTab::dirty))&&JOptionPane.showConfirmDialog(this,"Có thay đổi chưa lưu. Thoát và bỏ thay đổi?","Thoát JarStudio",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;debugger.stop();executor.shutdownNow();dispose();}
    private void updatePosition(){EditorTab tab=active();if(tab==null){position.setText("Java JAR Editor");setTitle("JarStudio — Java Assembly Editor");return;}String where=tab.session==null?String.valueOf(tab.file):tab.entry;position.setText("Ln "+tab.line()+"  |  "+where);setTitle("JarStudio — "+where+(tab.session==null?"":" ["+tab.session.displayName()+"]"));}
    private void updateActions(){boolean idle=busy==0,opened=!sessions.isEmpty();EditorTab tab=active();for(EditorTab document:documents)document.editor.setEditable(idle&&document.canEdit());boolean canSaveJar=opened;boolean canSaveFile=documents.stream().anyMatch(EditorTab::fileExternal);saveAction.setEnabled(idle&&(canSaveJar||canSaveFile));saveAsAction.setEnabled(idle&&canSaveJar);compileAction.setEnabled(idle&&tab!=null&&(tab.canEdit()||!tab.pendingConstants().isEmpty()));runAction.setEnabled(idle&&opened&&debugger.state()!=DebugController.State.RUNNING);pauseAction.setEnabled(debugger.state()==DebugController.State.RUNNING);stopAction.setEnabled(debugger.state()!=DebugController.State.DISCONNECTED);boolean paused=debugger.state()==DebugController.State.PAUSED;stepIntoAction.setEnabled(paused);stepOverAction.setEnabled(paused);stepOutAction.setEnabled(paused);openAction.setEnabled(idle);}
    private <T> CompletableFuture<T> job(String message,Callable<T> action,Consumer<T> success){
        CompletableFuture<T> future=new CompletableFuture<>();busy++;progress.setVisible(true);progress.setIndeterminate(true);status.setText(message);updateActions();
        currentJob=executor.submit(()->{try{T result=action.call();SwingUtilities.invokeLater(()->{try{success.accept(result);future.complete(result);}catch(Throwable error){showError(error);future.completeExceptionally(error);}finally{busy--;progress.setVisible(busy>0);updateActions();}});}catch(Throwable error){SwingUtilities.invokeLater(()->{busy--;progress.setVisible(busy>0);showError(error);updateActions();future.completeExceptionally(error);});}});return future;
    }
    private void showError(Throwable error){while(error instanceof java.util.concurrent.CompletionException&&error.getCause()!=null)error=error.getCause();String message=error.getMessage()==null?error.toString():error.getMessage();log("ERROR: "+message);status.setText("Chưa hoàn tất — xem Output / Lỗi biên dịch");if(error instanceof CompilerService.CompileFailure compile){errorModel.setRowCount(0);for(var diagnostic:compile.diagnostics())errorModel.addRow(new Object[]{diagnostic.severity(),diagnostic.line(),diagnostic.message()});tools.setSelectedIndex(2);}else tools.setSelectedIndex(0);if(!Boolean.getBoolean("jarstudio.test")){JTextArea text=new JTextArea(message,Math.min(16,message.split("\\R").length+2),80);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setFont(Ui.FONT);JOptionPane.showMessageDialog(this,new JScrollPane(text),"JarStudio",JOptionPane.WARNING_MESSAGE);}}
    public void log(String message){if(!SwingUtilities.isEventDispatchThread()){SwingUtilities.invokeLater(()->log(message));return;}String time=LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));output.append(time+"  "+message+"\n");if(output.getDocument().getLength()>300000)try{output.getDocument().remove(0,100000);}catch(Exception ignored){}output.setCaretPosition(output.getDocument().getLength());}
    public void shutdownForTest(){debugger.stop();executor.shutdownNow();dispose();}
}
