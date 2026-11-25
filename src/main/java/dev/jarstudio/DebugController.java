package dev.jarstudio;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.CharValue;
import com.sun.jdi.Field;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.ExceptionRequest;
import com.sun.jdi.request.MethodEntryRequest;
import com.sun.jdi.request.StepRequest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DebugController {
    public enum State {DISCONNECTED,RUNNING,PAUSED}
    public record Breakpoint(String className,int line,String method,String descriptor) {
        public static Breakpoint line(String owner,int line){return new Breakpoint(owner,line,null,null);}
        public static Breakpoint method(String owner,String name,String descriptor){return new Breakpoint(owner,-1,name,descriptor);}
        @Override public String toString(){return className+(method==null?":"+line:"."+method+descriptor);}
    }
    public record Frame(String owner,String method,int line,String source,int index) {
        @Override public String toString(){return owner+"."+method+" — "+(line>0?"line "+line:"native/no symbols");}
    }
    public record Variable(String name,String type,String display,Value value,StackFrame frame,LocalVariable local,ObjectReference object,Field field,Integer arrayIndex) {
        @Override public String toString(){return name+" = "+display+"  ("+type+")";}
    }
    public record Pause(String thread,List<Frame> frames,List<Variable> variables,String reason){}
    public record ThreadInfo(long id,String name,String status,boolean suspended,boolean current){}
    public interface Listener {
        void state(State state,String message);
        void output(String message);
        void paused(Pause pause);
    }
    private final Listener listener;
    private final CopyOnWriteArrayList<Breakpoint> breakpoints=new CopyOnWriteArrayList<>();
    private final Map<Breakpoint,List<EventRequest>> installed=new HashMap<>();
    private final Map<String,ClassPrepareRequest> preparation=new HashMap<>();
    private volatile VirtualMachine vm;
    private volatile State state=State.DISCONNECTED;
    private volatile EventSet pausedEvents;
    private volatile ThreadReference pausedThread;
    private volatile Process process;
    private volatile boolean owned;

    public DebugController(Listener listener){this.listener=listener;}
    public State state(){return state;}
    public List<Breakpoint> breakpoints(){return List.copyOf(breakpoints);}
    private void state(State value,String message){state=value;listener.state(value,message);}
    public synchronized void launch(String mainClass,String arguments,List<Path> classpath) throws Exception {
        if(vm!=null)throw new IllegalStateException("Đã có phiên debug đang chạy.");
        var connector=Bootstrap.virtualMachineManager().defaultConnector();var args=connector.defaultArguments();
        args.get("main").setValue(mainClass+(arguments==null||arguments.isBlank()?"":" "+arguments));
        args.get("options").setValue("-Dfile.encoding=UTF-8 -cp "+quote(String.join(java.io.File.pathSeparator,classpath.stream().map(Path::toString).toList())));
        if(args.containsKey("home"))args.get("home").setValue(System.getProperty("java.home"));
        args.get("suspend").setValue("true");
        vm=connector.launch(args);owned=true;process=vm.process();
        read(process.getInputStream(),"");read(process.getErrorStream(),"stderr: ");
        connected("Đã chạy JVM để debug.");
    }
    public synchronized void attach(String host,int port) throws Exception {
        if(vm!=null)throw new IllegalStateException("Đã có phiên debug đang chạy.");
        var connector=Bootstrap.virtualMachineManager().attachingConnectors().stream().filter(value->value.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
        var args=connector.defaultArguments();args.get("hostname").setValue(host);args.get("port").setValue(String.valueOf(port));
        if(args.containsKey("timeout"))args.get("timeout").setValue("10000");
        vm=connector.attach(args);owned=false;process=null;connected("Đã gắn debugger vào "+host+":"+port);
    }
    private void connected(String message) throws Exception {
        installed.clear();preparation.clear();
        for(Breakpoint breakpoint:breakpoints)install(breakpoint);
        ExceptionRequest exceptions=vm.eventRequestManager().createExceptionRequest(null,false,true);exceptions.setSuspendPolicy(EventRequest.SUSPEND_ALL);exceptions.enable();
        state(State.RUNNING,message);
        Thread events=new Thread(this::events,"jarstudio-jdi-events");events.setDaemon(true);events.start();
    }
    public static String quote(String value){return "\""+value.replace("\"","\\\"")+"\"";}
    private void read(InputStream stream,String prefix) {
        Thread thread=new Thread(()->{try(var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null)listener.output(prefix+line);}catch(Exception ignored){}},"jarstudio-target-output");
        thread.setDaemon(true);thread.start();
    }
    public synchronized void toggle(Breakpoint breakpoint) throws Exception {
        if(breakpoints.remove(breakpoint)) {
            List<EventRequest> requests=installed.remove(breakpoint);
            if(vm!=null&&requests!=null&&!requests.isEmpty())vm.eventRequestManager().deleteEventRequests(requests);
        } else {breakpoints.add(breakpoint);if(vm!=null)install(breakpoint);}
    }
    private synchronized void install(Breakpoint breakpoint) throws Exception {
        VirtualMachine machine=vm;if(machine==null)return;
        if(!preparation.containsKey(breakpoint.className())) {
            ClassPrepareRequest request=machine.eventRequestManager().createClassPrepareRequest();request.addClassFilter(breakpoint.className());request.setSuspendPolicy(EventRequest.SUSPEND_ALL);request.enable();preparation.put(breakpoint.className(),request);
        }
        for(ReferenceType type:machine.classesByName(breakpoint.className()))if(type.isPrepared())install(type,breakpoint);
    }
    private synchronized void install(ReferenceType type,Breakpoint breakpoint) throws Exception {
        List<EventRequest> requests=installed.computeIfAbsent(breakpoint,key->new ArrayList<>());
        if(breakpoint.method()!=null) {
            if(!requests.isEmpty())return;
            MethodEntryRequest request=vm.eventRequestManager().createMethodEntryRequest();request.addClassFilter(type);request.setSuspendPolicy(EventRequest.SUSPEND_ALL);request.enable();requests.add(request);return;
        }
        try {
            List<Location> locations=type.locationsOfLine(breakpoint.line());
            if(locations.isEmpty())listener.output("Không có lệnh tại "+breakpoint+". Biên dịch Java hiện tại để đồng bộ dòng, hoặc đặt breakpoint theo hàm.");
            for(Location location:locations) {
                if(requests.stream().anyMatch(request->request instanceof BreakpointRequest existing&&existing.location().equals(location)))continue;
                BreakpointRequest request=vm.eventRequestManager().createBreakpointRequest(location);request.setSuspendPolicy(EventRequest.SUSPEND_ALL);request.enable();requests.add(request);
            }
        }catch(AbsentInformationException error){listener.output(type.name()+" không có line debug symbols; dùng breakpoint theo hàm hoặc biên dịch lại.");}
    }
    private void events() {
        try {
            while(vm!=null) {
                EventSet set=vm.eventQueue().remove(1000);if(set==null)continue;
                ThreadReference stop=null;String reason=null;
                for(var event:set) {
                    if(event instanceof ClassPrepareEvent prepared) {
                        for(Breakpoint breakpoint:breakpoints)if(breakpoint.className().equals(prepared.referenceType().name()))install(prepared.referenceType(),breakpoint);
                    }
                    else if(event instanceof BreakpointEvent hit){stop=hit.thread();reason="Breakpoint";}
                    else if(event instanceof StepEvent step){stop=step.thread();reason="Step";vm.eventRequestManager().deleteEventRequest(step.request());}
                    else if(event instanceof MethodEntryEvent entry) {
                        boolean selected=breakpoints.stream().anyMatch(value->value.className().equals(entry.location().declaringType().name())&&value.method()!=null&&value.method().equals(entry.method().name())&&value.descriptor().equals(entry.method().signature()));
                        if(selected){stop=entry.thread();reason="Method entry";}
                    } else if(event instanceof ExceptionEvent exception){stop=exception.thread();reason="Unhandled exception: "+exception.exception().referenceType().name();}
                    else if(event instanceof VMDisconnectEvent||event instanceof VMDeathEvent){disconnected("JVM đã kết thúc.");return;}
                }
                if(stop!=null) {
                    pausedEvents=set;pausedThread=stop;state(State.PAUSED,reason+" — "+stop.name());listener.paused(inspect(0,reason));
                } else set.resume();
            }
        }catch(InterruptedException error){Thread.currentThread().interrupt();}
        catch(VMDisconnectedException error){disconnected("Debugger đã ngắt kết nối.");}
        catch(Exception error){listener.output("Debugger: "+error);disconnected("Phiên debug đã dừng.");}
    }
    public synchronized Pause inspect(int frameIndex,String reason) throws Exception {
        if(pausedThread==null||state!=State.PAUSED)throw new IllegalStateException("JVM chưa dừng tại breakpoint.");
        List<StackFrame> frames=pausedThread.frames();List<Frame> stack=new ArrayList<>();
        for(int i=0;i<frames.size();i++) {
            Location location=frames.get(i).location();String source="";try{source=location.sourceName();}catch(AbsentInformationException ignored){}
            stack.add(new Frame(location.declaringType().name(),location.method().name(),location.lineNumber(),source,i));
        }
        List<Variable> locals=new ArrayList<>();
        if(frameIndex>=0&&frameIndex<frames.size()) {
            StackFrame frame=frames.get(frameIndex);ObjectReference self=frame.thisObject();
            if(self!=null)locals.add(variable("this",self,frame,null,null,null,null));
            try {for(LocalVariable local:frame.visibleVariables())locals.add(variable(local.name(),frame.getValue(local),frame,local,null,null,null));}
            catch(AbsentInformationException error){locals.add(new Variable("<không có local debug symbols>","","Biên dịch lại class với debug symbols.",null,frame,null,null,null,null));}
        }
        return new Pause(pausedThread.name(),List.copyOf(stack),List.copyOf(locals),reason);
    }
    private Variable variable(String name,Value value,StackFrame frame,LocalVariable local,ObjectReference object,Field field,Integer index) {
        String type=value==null?local!=null?local.typeName():field!=null?field.typeName():object instanceof ArrayReference array?((com.sun.jdi.ArrayType)array.type()).componentTypeName():"null":value.type().name();
        return new Variable(name,type,display(value),value,frame,local,object,field,index);
    }
    public synchronized List<ThreadInfo> threads() {
        if(vm==null)return List.of();List<ThreadInfo> result=new ArrayList<>();
        for(ThreadReference thread:vm.allThreads()) {
            String status=switch(thread.status()){case ThreadReference.THREAD_STATUS_RUNNING->"Running";case ThreadReference.THREAD_STATUS_SLEEPING->"Sleeping";case ThreadReference.THREAD_STATUS_MONITOR->"Monitor";case ThreadReference.THREAD_STATUS_WAIT->"Waiting";case ThreadReference.THREAD_STATUS_ZOMBIE->"Ended";default->"Unknown";};
            result.add(new ThreadInfo(thread.uniqueID(),thread.name(),status,thread.isSuspended(),thread.equals(pausedThread)));
        }
        result.sort(java.util.Comparator.comparingLong(ThreadInfo::id));return List.copyOf(result);
    }
    public synchronized Pause selectThread(long id) throws Exception {
        if(vm==null||state!=State.PAUSED)throw new IllegalStateException("Tạm dừng JVM trước khi chọn thread.");
        ThreadReference selected=vm.allThreads().stream().filter(thread->thread.uniqueID()==id).findFirst().orElseThrow(()->new IllegalArgumentException("Thread đã kết thúc."));
        if(!selected.isSuspended()||selected.frameCount()==0)throw new IllegalArgumentException("Thread này chưa có stack frame để xem.");
        pausedThread=selected;return inspect(0,"Thread "+selected.name());
    }
    public List<Variable> children(Variable parent) {
        if(state!=State.PAUSED)return List.of();List<Variable> children=new ArrayList<>();
        try {
            if(parent.value() instanceof ArrayReference array) {
                int count=Math.min(array.length(),1000);
                for(int i=0;i<count;i++)children.add(variable("["+i+"]",array.getValue(i),parent.frame(),null,array,null,i));
                if(array.length()>count)children.add(new Variable("…","",(array.length()-count)+" phần tử khác",null,null,null,null,null,null));
            } else if(parent.value() instanceof ObjectReference object&&!(object instanceof StringReference)) {
                for(Field field:object.referenceType().allFields())children.add(variable(field.name(),object.getValue(field),parent.frame(),null,object,field,null));
            }
        }catch(Exception error){children.add(new Variable("<error>","",error.toString(),null,null,null,null,null,null));}
        return children;
    }
    public String watch(String expression,int frameIndex) throws Exception {
        Pause pause=inspect(frameIndex,"Watch");String[] parts=expression.trim().split("\\.");
        if(parts.length==0||parts[0].isBlank())return "";
        Variable value=pause.variables().stream().filter(row->row.name().equals(parts[0])).findFirst().orElse(null);
        if(value==null)throw new IllegalArgumentException("Không tìm thấy biến "+parts[0]+" trong stack frame này.");
        for(int index=1;index<parts.length;index++) {
            String part=parts[index];
            value=children(value).stream().filter(row->row.name().equals(part)).findFirst().orElseThrow(()->new IllegalArgumentException("Không tìm thấy field "+part));
        }
        return value.display();
    }
    public synchronized void set(Variable variable,String text) throws Exception {
        if(state!=State.PAUSED)throw new IllegalStateException("Hãy dừng ở breakpoint trước khi sửa biến.");
        Value value=mirror(variable.type(),text);
        if(variable.local()!=null)variable.frame().setValue(variable.local(),value);
        else if(variable.arrayIndex()!=null)((ArrayReference)variable.object()).setValue(variable.arrayIndex(),value);
        else if(variable.field()!=null) {
            if(variable.field().isStatic()) {
                if(variable.field().declaringType() instanceof com.sun.jdi.ClassType type)type.setValue(variable.field(),value);
                else throw new IllegalArgumentException("Không sửa được field tĩnh này.");
            } else variable.object().setValue(variable.field(),value);
        } else throw new IllegalArgumentException("Biến này chỉ đọc.");
    }
    private Value mirror(String type,String text) {
        return switch(type) {
            case "boolean" -> {if(!text.equalsIgnoreCase("true")&&!text.equalsIgnoreCase("false"))throw new IllegalArgumentException("Nhập true hoặc false.");yield vm.mirrorOf(Boolean.parseBoolean(text));}
            case "byte" -> vm.mirrorOf(Byte.decode(text));
            case "short" -> vm.mirrorOf(Short.decode(text));
            case "char" -> {if(text.length()!=1)throw new IllegalArgumentException("Nhập đúng một ký tự.");yield vm.mirrorOf(text.charAt(0));}
            case "int" -> vm.mirrorOf(Integer.decode(text));
            case "long" -> vm.mirrorOf(Long.decode(text));
            case "float" -> vm.mirrorOf(Float.parseFloat(text));
            case "double" -> vm.mirrorOf(Double.parseDouble(text));
            case "java.lang.String" -> vm.mirrorOf(text);
            default -> {if(!text.equals("null"))throw new IllegalArgumentException("Object chỉ có thể đặt về null; số, boolean và String hỗ trợ sửa trực tiếp.");yield null;}
        };
    }
    public static String display(Value value) {
        if(value==null)return "null";
        if(value instanceof StringReference string){String text=string.value();return "\""+(text.length()>1000?text.substring(0,1000)+"…":text).replace("\n","\\n").replace("\r","\\r")+"\"";}
        if(value instanceof ArrayReference array)return array.referenceType().name()+" [length="+array.length()+"]";
        if(value instanceof ObjectReference object)return object.referenceType().name()+" @"+object.uniqueID();
        return value.toString();
    }
    public synchronized void resume() {
        if(vm==null)return;EventSet events=pausedEvents;pausedEvents=null;pausedThread=null;
        state(State.RUNNING,"Đang chạy");if(events!=null)events.resume();else vm.resume();
    }
    public synchronized void pause() throws Exception {
        if(vm==null||state!=State.RUNNING)return;vm.suspend();
        pausedThread=vm.allThreads().stream().filter(thread->{try{return thread.isSuspended()&&thread.frameCount()>0&&!thread.name().contains("Reference Handler");}catch(Exception error){return false;}}).findFirst().orElseThrow();
        pausedEvents=null;state(State.PAUSED,"Tạm dừng");listener.paused(inspect(0,"Pause"));
    }
    public synchronized void step(int depth) throws Exception {
        if(vm==null||pausedThread==null)throw new IllegalStateException("Dừng tại breakpoint trước khi step.");
        for(StepRequest existing:new ArrayList<>(vm.eventRequestManager().stepRequests()))vm.eventRequestManager().deleteEventRequest(existing);
        StepRequest request=vm.eventRequestManager().createStepRequest(pausedThread,StepRequest.STEP_LINE,depth);
        for(String excluded:List.of("java.*","javax.*","sun.*","com.sun.*","jdk.*","org.microemu.*"))request.addClassExclusionFilter(excluded);
        request.addCountFilter(1);request.setSuspendPolicy(EventRequest.SUSPEND_ALL);request.enable();resume();
    }
    public synchronized int hotSwap(Map<String,byte[]> classes) {
        if(vm==null||!vm.canRedefineClasses())throw new IllegalStateException("JVM này không hỗ trợ thay code đang chạy.");
        Map<ReferenceType,byte[]> replacements=new LinkedHashMap<>();
        classes.forEach((name,bytes)->{String owner=ClassTools.info(bytes).binaryName();for(ReferenceType type:vm.classesByName(owner))replacements.put(type,bytes);});
        if(replacements.isEmpty())throw new IllegalStateException("Class chưa được JVM nạp; hãy chạy tới class đó trước.");
        vm.redefineClasses(replacements);
        java.util.Set<String> owners=new java.util.HashSet<>();for(ReferenceType type:replacements.keySet())owners.add(type.name());
        for(Breakpoint breakpoint:breakpoints)if(owners.contains(breakpoint.className())) {
            List<EventRequest> old=installed.remove(breakpoint);
            if(old!=null)for(EventRequest request:old)try{vm.eventRequestManager().deleteEventRequest(request);}catch(Exception ignored){}
            try{install(breakpoint);}catch(Exception error){listener.output("HotSwap: không đặt lại được "+breakpoint+": "+error.getMessage());}
        }
        return replacements.size();
    }
    public synchronized void stop() {
        VirtualMachine machine=vm;if(machine==null)return;
        try {if(owned)machine.exit(0);else machine.dispose();}catch(Exception ignored){}
        disconnected(owned?"Đã dừng JVM của phiên debug.":"Đã tách debugger khỏi JVM.");
    }
    private synchronized void disconnected(String reason) {
        vm=null;pausedThread=null;pausedEvents=null;installed.clear();preparation.clear();state(State.DISCONNECTED,reason);
    }
}
