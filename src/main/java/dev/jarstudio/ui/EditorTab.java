package dev.jarstudio.ui;

import dev.jarstudio.ClassTools;
import dev.jarstudio.CompilerService;
import dev.jarstudio.JarSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JOptionPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.fife.ui.rtextarea.GutterIconInfo;

public final class EditorTab extends JPanel {
    public record Loaded(byte[] bytes,ClassTools.Info info,String java,String decompiler,String note){}
    public interface Callback {
        void changed(EditorTab tab);
        void editConstant(EditorTab tab,ClassTools.Constant constant);
        void breakpoint(EditorTab tab,int line);
        void fileSaved(Path file);
        void complete(EditorTab tab);
    }
    public final JarSession session;
    public final String entry;
    /** Tệp nằm ngoài JAR (mở/sửa/lưu trực tiếp như trình soạn thảo văn bản). Null với entry trong JAR. */
    public final Path file;
    public final long[] fileStamp=new long[2];
    public final RSyntaxTextArea editor=new RSyntaxTextArea();
    public final RTextScrollPane scroll=new RTextScrollPane(editor);
    public final JTabbedPane views=new JTabbedPane();
    public final ClassTools.Info info;
    public final JTable constants=new JTable();
    private final DefaultTableModel constantModel=new DefaultTableModel(new String[]{"Kiểu","Giá trị","Vị trí"},0){public boolean isCellEditable(int r,int c){return false;}};
    private List<ClassTools.Constant> values=List.of();
    private final Map<Integer,GutterIconInfo> breakpointIcons=new LinkedHashMap<>();
    private final Map<String,String> constantChanges=new LinkedHashMap<>();
    private final Callback callback;
    private String baseline;
    private Charset charset=StandardCharsets.UTF_8;
    private String newline="\n";
    private byte[] bom=new byte[0];
    private Object executionHighlight;
    private boolean ignoreChanges;
    private boolean editable;
    private final JLabel hint=new JLabel();
    private final RSyntaxTextArea bytecodeEditor=new RSyntaxTextArea();
    private final RSyntaxTextArea hexEditor=new RSyntaxTextArea();
    private byte[] fileOriginal=new byte[0];
    private final long[] fileOriginalStamp=new long[2];
    private final List<Integer> errorLines=new ArrayList<>();
    private final List<GutterIconInfo> errorIcons=new ArrayList<>();

    public EditorTab(JarSession session,String entry,Loaded loaded,Callback callback) {
        this(session,entry,loaded,null,loaded.bytes(),callback);
    }
    public EditorTab(JarSession session,String entry,Loaded loaded,Path file,byte[] original,Callback callback) {
        super(new BorderLayout());this.session=session;this.entry=entry;this.info=loaded.info();this.callback=callback;this.file=file;
        if(file!=null){this.fileOriginal=original.clone();try{long stamp=java.nio.file.Files.getLastModifiedTime(file).toMillis();long size=java.nio.file.Files.size(file);fileOriginalStamp[0]=stamp;fileOriginalStamp[1]=size;fileStamp[0]=stamp;fileStamp[1]=size;}catch(Exception ignored){}}
        setBorder(BorderFactory.createEmptyBorder());
        editor.setFont(Ui.CODE);editor.setTabSize(4);editor.setTabsEmulated(true);editor.setCodeFoldingEnabled(true);editor.setMarkOccurrences(true);editor.setAntiAliasingEnabled(true);editor.setBracketMatchingEnabled(true);
        scroll.setLineNumbersEnabled(true);scroll.setFoldIndicatorEnabled(true);scroll.setIconRowHeaderEnabled(true);
        hint.setBorder(BorderFactory.createEmptyBorder(4,8,4,8));hint.setOpaque(true);hint.setBackground(new Color(246,248,251));hint.setForeground(new Color(91,103,118));
        if(info!=null) {
            editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JAVA);
            editable=loaded.java()!=null&&!loaded.java().isBlank();
            baseline=editable?loaded.java():"// Không tạo được Java source cho class này.\n// Vẫn có thể xem Bytecode/Hex hoặc đổi Hằng số.\n\n"+loaded.note();
            editor.setEditable(editable);editor.setText(baseline);editor.setCaretPosition(0);
            views.addTab("Java",scroll);
            bytecodeEditor.setText(ClassTools.bytecode(loaded.bytes()));bytecodeEditor.setFont(Ui.CODE);bytecodeEditor.setEditable(false);bytecodeEditor.setCaretPosition(0);
            views.addTab("Bytecode",new RTextScrollPane(bytecodeEditor));
            JPanel panel=new JPanel(new BorderLayout());constants.setModel(constantModel);constants.setAutoCreateRowSorter(true);constants.setRowHeight(24);constants.setFont(Ui.FONT);
            panel.add(new JScrollPane(constants),BorderLayout.CENTER);
            JPanel bar=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));JButton change=Ui.button("Sửa giá trị…","compile",()->editSelectedConstant());bar.add(change);panel.add(bar,BorderLayout.SOUTH);
            constants.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2)editSelectedConstant();}});
            views.addTab("Hằng số",panel);refreshConstants(loaded.bytes());
            hexEditor.setFont(Ui.CODE);hexEditor.setEditable(false);views.addTab("Hex",new RTextScrollPane(hexEditor));
            hint.setText("Java "+info.javaVersion()+"  |  "+loaded.decompiler()+"  |  Sửa Java → Biên dịch (F6) → Lưu JAR (Ctrl+S)");
            for(java.awt.Component component:scroll.getGutter().getComponents())component.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent event){
                if(event.getButton()!=java.awt.event.MouseEvent.BUTTON1)return;
                try {java.awt.Point point=javax.swing.SwingUtilities.convertPoint(event.getComponent(),event.getPoint(),editor);int offset=editor.viewToModel2D(new java.awt.Point(2,point.y));callback.breakpoint(EditorTab.this,editor.getLineOfOffset(Math.max(0,offset))+1);}catch(Exception ignored){}
            }});
        } else {
            editable=isText(entry,loaded.bytes());
            if(editable) {
                baseline=decode(loaded.bytes());newline=baseline.contains("\r\n")?"\r\n":baseline.contains("\r")?"\r":"\n";baseline=baseline.replace("\r\n","\n").replace('\r','\n');editor.setText(baseline);editor.setSyntaxEditingStyle(style(entry));editor.setEditable(true);views.addTab("Nội dung",scroll);hint.setText("Sửa nội dung rồi Lưu JAR (Ctrl+S). Tài nguyên khác được giữ nguyên.");
            } else {
                baseline="";editor.setEditable(false);editor.setText(hex(loaded.bytes()));editor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);views.addTab("Hex",scroll);
                hint.setText("Tệp nhị phân — dùng Thay tệp để nhập nội dung mới.");
                try {
                    java.awt.image.BufferedImage image=null;
                    try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(loaded.bytes()))) {
                        var readers=javax.imageio.ImageIO.getImageReaders(input);
                        if(readers.hasNext()) {
                            var reader=readers.next();
                            try {reader.setInput(input);long pixels=(long)reader.getWidth(0)*reader.getHeight(0);if(pixels>0&&pixels<=24_000_000)image=reader.read(0);}finally{reader.dispose();}
                        }
                    }
                    if(image!=null) {
                        JLabel preview=new JLabel(new javax.swing.ImageIcon(image));preview.setHorizontalAlignment(SwingConstants.CENTER);JScrollPane picture=new JScrollPane(preview);views.insertTab("Ảnh",null,picture,null,0);views.setSelectedIndex(0);
                    }
                } catch(Exception ignored){}
            }
            editor.setCaretPosition(0);
        }
        add(views,BorderLayout.CENTER);add(hint,BorderLayout.SOUTH);
        editor.getDocument().addDocumentListener(new DocumentListener(){public void insertUpdate(DocumentEvent e){changed();}public void removeUpdate(DocumentEvent e){changed();}public void changedUpdate(DocumentEvent e){changed();}private void changed(){if(!ignoreChanges)callback.changed(EditorTab.this);}});
        editor.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("control space"),"jarstudio.complete");
        editor.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("shift insert"),"jarstudio.complete");
        editor.getActionMap().put("jarstudio.complete",new javax.swing.AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent event){callback.complete(EditorTab.this);}});
        editor.discardAllEdits();
    }
    public void markClean(){ignoreChanges=true;try{baseline=editor.getText();constantChanges.clear();}finally{ignoreChanges=false;}callback.changed(this);}
    public boolean isClass(){return info!=null;}
    public boolean canEdit(){return editable;}
    public String source(){return editor.getText();}
    public boolean sourceDirty(){return editable&&!editor.getText().equals(baseline);}
    public boolean dirty(){return file!=null?sourceDirty():sourceDirty()||!constantChanges.isEmpty();}
    public boolean fileExternal(){return file!=null;}
    public boolean externalChanged() {
        if(file==null)return false;
        try {return Files.getLastModifiedTime(file).toMillis()!=fileStamp[0]||Files.size(file)!=fileStamp[1];}
        catch(Exception error){return true;}
    }
    public void saveFile() throws Exception {
        if(file==null)throw new IllegalStateException("Tab này thuộc JAR; dùng Lưu JAR (Ctrl+S).");
        byte[] bytes=resourceBytes();
        if(externalChanged()&&JOptionPane.showConfirmDialog(null,"Tệp đã thay đổi ở bên ngoài:\n"+file+"\n\nGhi đè bằng nội dung đang mở?","Tệp đã thay đổi",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)throw new IOException("Đã hủy vì tệp thay đổi ngoài.");
        Files.write(file,bytes);
        baseline=editor.getText();
        try{fileStamp[0]=Files.getLastModifiedTime(file).toMillis();fileStamp[1]=Files.size(file);}catch(Exception ignored){}
        callback.fileSaved(file);callback.changed(this);
    }
    public boolean hasErrors(){return !errorLines.isEmpty();}
    public void setErrors(List<CompilerService.Diagnostic> diagnostics) {
        clearErrorMarkers();errorLines.clear();
        for(var diagnostic:diagnostics) {
            if(diagnostic.line()<1||diagnostic.line()>editor.getLineCount())continue;
            try {
                boolean warning=diagnostic.severity().toLowerCase().startsWith("w");
                GutterIconInfo marker=scroll.getGutter().addLineTrackingIcon(diagnostic.line()-1,Ui.icon(warning?"breakpoint":"stop"),(warning?"Cảnh báo":"Lỗi")+" dòng "+diagnostic.line()+": "+diagnostic.message());
                errorIcons.add(marker);errorLines.add(diagnostic.line());
            }catch(Exception ignored){}
        }
    }
    public void clearErrorMarkers(){for(var marker:errorIcons)scroll.getGutter().removeTrackingIcon(marker);errorIcons.clear();errorLines.clear();}
    public void jumpError(int index) {
        if(index<0||index>=errorLines.size())return;
        jumpLine(errorLines.get(index));
    }
    public int errorCount(){return errorLines.size();}
    public byte[] originalBytes(){return file!=null?fileOriginal.clone():new byte[0];}
    /** Offset bắt đầu từ ký tự định danh đứng trước caret. */
    public int wordStart(){
        int caret=editor.getCaretPosition();
        String text;
        try{text=editor.getText(0,Math.max(0,caret));}catch(Exception error){return caret;}
        int i=text.length();
        while(i>0&&Character.isJavaIdentifierPart(text.charAt(i-1)))i--;
        return i;
    }
    public String caretWord(){
        int caret=editor.getCaretPosition();int start=wordStart();
        try{return editor.getDocument().getText(start,Math.max(0,caret-start));}catch(Exception error){return "";}
    }
    public void completeWord(String suggestion) {
        if(suggestion==null)return;
        int caret=editor.getCaretPosition();int start=wordStart();
        try {
            // Replace the identifier prefix before the caret with the full suggestion.
            editor.getDocument().remove(start,Math.max(0,caret-start));
            editor.getDocument().insertString(start,suggestion,null);
            editor.setCaretPosition(start+suggestion.length());
        }catch(Exception error){/* bộ tự hoàn thành không chặn thao tác */}
    }
    public Map<String,String> pendingConstants(){return Map.copyOf(constantChanges);}
    public void editedConstant(ClassTools.Constant row,String text){constantChanges.put(row.key(),text);refreshConstantModel();callback.changed(this);}
    public void refreshConstants(byte[] bytes){values=ClassTools.constants(bytes);constantChanges.clear();refreshConstantModel();bytecodeEditor.setText(ClassTools.bytecode(bytes));bytecodeEditor.setCaretPosition(0);hexEditor.setText(hex(bytes));hexEditor.setCaretPosition(0);}
    private void refreshConstantModel(){constantModel.setRowCount(0);for(ClassTools.Constant value:values)constantModel.addRow(new Object[]{value.type(),constantChanges.getOrDefault(value.key(),value.text()).replace("\n","\\n").replace("\r","\\r"),value.location()});}
    private void editSelectedConstant(){int row=constants.getSelectedRow();if(row>=0)callback.editConstant(this,values.get(constants.convertRowIndexToModel(row)));}
    public byte[] resourceBytes(){
        byte[] data;
        try{ByteBuffer encoded=charset.newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).onMalformedInput(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(editor.getText().replace("\r\n","\n").replace('\r','\n').replace("\n",newline)));data=new byte[encoded.remaining()];encoded.get(data);}
        catch(CharacterCodingException error){throw new IllegalArgumentException("Tệp này dùng "+charset.name()+" và không chứa được ký tự vừa nhập. Hãy thay bằng tệp UTF-8 hoặc dùng ký tự phù hợp.",error);}
        if(bom.length==0)return data;
        byte[] result=new byte[bom.length+data.length];System.arraycopy(bom,0,result,0,bom.length);System.arraycopy(data,0,result,bom.length,data.length);return result;
    }
    public void compiled(String source,byte[] bytes){baseline=source;constantChanges.clear();if(info!=null)refreshConstants(bytes);callback.changed(this);}
    public void replaceSource(String text,byte[] bytes) {
        ignoreChanges=true;try{editor.setText(text);baseline=text;editor.setCaretPosition(0);editor.discardAllEdits();refreshConstants(bytes);}finally{ignoreChanges=false;}callback.changed(this);
    }
    public void markSaved(){baseline=editor.getText();constantChanges.clear();callback.changed(this);}
    public String title(){String name=entry.substring(entry.lastIndexOf('/')+1);if(name.endsWith(".class"))name=name.substring(0,name.length()-6)+".java";return name+(dirty()?" *":"");}
    public void jump(String member) {
        if(member==null||member.isBlank())return;views.setSelectedIndex(0);
        String name=member.equals("<init>")&&info!=null?info.internalName().substring(info.internalName().lastIndexOf('/')+1):member;
        var pattern=java.util.regex.Pattern.compile("\\b"+java.util.regex.Pattern.quote(name)+"\\s*\\(").matcher(editor.getText());
        if(pattern.find()){editor.setCaretPosition(pattern.start());editor.requestFocusInWindow();}
    }
    public void jumpLine(int line){try{views.setSelectedIndex(0);editor.setCaretPosition(editor.getLineStartOffset(Math.min(Math.max(1,line),editor.getLineCount())-1));editor.requestFocusInWindow();}catch(Exception ignored){}}
    public void showExecutionLine(int line){clearExecutionLine();try{int index=Math.min(Math.max(1,line),editor.getLineCount())-1;executionHighlight=editor.getHighlighter().addHighlight(editor.getLineStartOffset(index),editor.getLineEndOffset(index),new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(255,234,154)));jumpLine(line);}catch(Exception ignored){}}
    public void clearExecutionLine(){if(executionHighlight!=null){editor.getHighlighter().removeHighlight(executionHighlight);executionHighlight=null;}}
    public int line(){try{return editor.getLineOfOffset(editor.getCaretPosition())+1;}catch(Exception ignored){return 1;}}
    public void toggleBreakpointIcon(int line) throws Exception {
        GutterIconInfo existing=breakpointIcons.remove(line);
        if(existing!=null)scroll.getGutter().removeTrackingIcon(existing);
        else breakpointIcons.put(line,scroll.getGutter().addLineTrackingIcon(line-1,Ui.icon("breakpoint"),"Breakpoint "+line));
    }
    public void theme(boolean dark){try(var input=EditorTab.class.getResourceAsStream("/org/fife/ui/rsyntaxtextarea/themes/"+(dark?"dark.xml":"default.xml"))){if(input!=null)Theme.load(input).apply(editor);}catch(Exception ignored){}}
    private String decode(byte[] bytes) {
        int offset=0;
        if(bytes.length>=3&&bytes[0]==(byte)0xef&&bytes[1]==(byte)0xbb&&bytes[2]==(byte)0xbf){bom=new byte[]{bytes[0],bytes[1],bytes[2]};offset=3;}
        else if(bytes.length>=2&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe){charset=StandardCharsets.UTF_16LE;bom=new byte[]{bytes[0],bytes[1]};offset=2;}
        else if(bytes.length>=2&&bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff){charset=StandardCharsets.UTF_16BE;bom=new byte[]{bytes[0],bytes[1]};offset=2;}
        try{return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();}
        catch(CharacterCodingException error){charset=StandardCharsets.ISO_8859_1;return new String(bytes,charset);}
    }
    private static String style(String name) {
        String lower=name.toLowerCase();if(lower.endsWith(".xml")||lower.endsWith(".fxml")||lower.endsWith(".svg"))return SyntaxConstants.SYNTAX_STYLE_XML;
        if(lower.endsWith(".json"))return SyntaxConstants.SYNTAX_STYLE_JSON;if(lower.endsWith(".java"))return SyntaxConstants.SYNTAX_STYLE_JAVA;
        if(lower.endsWith(".properties"))return SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;if(lower.endsWith(".html"))return SyntaxConstants.SYNTAX_STYLE_HTML;
        return SyntaxConstants.SYNTAX_STYLE_NONE;
    }
    private static boolean isText(String name,byte[] bytes) {
        if(bytes.length>4*1024*1024)return false;
        if(name.toLowerCase().matches(".*\\.(txt|mf|properties|xml|json|java|md|csv|html|css|js|yml|yaml|conf|ini|fxml|svg|pom)$"))return true;
        if(bytes.length>=2&&((bytes[0]==(byte)0xff&&bytes[1]==(byte)0xfe)||(bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)))return true;
        for(int i=0;i<Math.min(bytes.length,8192);i++)if(bytes[i]==0||((bytes[i]&255)<9))return false;
        try{StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));return true;}catch(Exception error){return false;}
    }
    private static String hex(byte[] bytes) {
        StringBuilder result=new StringBuilder();int count=Math.min(bytes.length,64*1024);
        for(int offset=0;offset<count;offset+=16) {
            result.append(String.format("%08X  ",offset));
            for(int i=0;i<16;i++)result.append(offset+i<count?String.format("%02X ",bytes[offset+i]&255):"   ");
            result.append(" ");for(int i=0;i<16&&offset+i<count;i++){int value=bytes[offset+i]&255;result.append(value>=32&&value<127?(char)value:'.');}result.append('\n');
        }
        if(bytes.length>count)result.append("\n… chỉ hiển thị 64 KB đầu tiên.");return result.toString();
    }
}
