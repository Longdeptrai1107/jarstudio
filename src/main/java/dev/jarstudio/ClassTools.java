package dev.jarstudio;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.TraceClassVisitor;

public final class ClassTools {
    public record Member(boolean method,String name,String descriptor,int access,String display) {
        @Override public String toString(){return display;}
    }
    public record Info(String internalName,String superName,List<String> interfaces,int access,int major,int minor,List<Member> members) {
        public String binaryName(){return internalName.replace('/','.');}
        public int javaVersion(){return major-44;}
    }
    public record Constant(String key,String type,Object value,String location) {
        public String text(){return String.valueOf(value);}
    }
    private ClassTools(){}
    public static ClassNode node(byte[] data) {ClassNode node=new ClassNode();new ClassReader(data).accept(node,0);return node;}
    public static Info info(byte[] data) {
        ClassReader reader=new ClassReader(data);ClassNode node=new ClassNode();reader.accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        List<Member> members=new ArrayList<>();
        for(FieldNode field:node.fields)members.add(new Member(false,field.name,field.desc,field.access,field.name+" : "+shortType(Type.getType(field.desc))));
        for(MethodNode method:node.methods) {
            String name=method.name.equals("<init>")?node.name.substring(node.name.lastIndexOf('/')+1):method.name.equals("<clinit>")?"static { }":method.name;
            String args=String.join(", ",java.util.Arrays.stream(Type.getArgumentTypes(method.desc)).map(ClassTools::shortType).toList());
            members.add(new Member(true,method.name,method.desc,method.access,name+"("+args+") : "+shortType(Type.getReturnType(method.desc))));
        }
        return new Info(node.name,node.superName,List.copyOf(node.interfaces),node.access,reader.readUnsignedShort(6),reader.readUnsignedShort(4),List.copyOf(members));
    }
    private static String shortType(Type type){String name=type.getClassName();int dot=name.lastIndexOf('.');return dot<0?name:name.substring(dot+1);}
    public static String bytecode(byte[] data) {
        StringWriter text=new StringWriter();new ClassReader(data).accept(new TraceClassVisitor(new PrintWriter(text)),0);return text.toString();
    }
    private static Object constant(AbstractInsnNode instruction) {
        if(instruction instanceof LdcInsnNode ldc)return scalar(ldc.cst)?ldc.cst:null;
        if(instruction instanceof IntInsnNode integer&&(integer.getOpcode()==Opcodes.BIPUSH||integer.getOpcode()==Opcodes.SIPUSH))return integer.operand;
        int opcode=instruction.getOpcode();
        if(opcode>=Opcodes.ICONST_M1&&opcode<=Opcodes.ICONST_5)return opcode-Opcodes.ICONST_0;
        if(opcode>=Opcodes.LCONST_0&&opcode<=Opcodes.LCONST_1)return (long)(opcode-Opcodes.LCONST_0);
        if(opcode>=Opcodes.FCONST_0&&opcode<=Opcodes.FCONST_2)return (float)(opcode-Opcodes.FCONST_0);
        if(opcode>=Opcodes.DCONST_0&&opcode<=Opcodes.DCONST_1)return (double)(opcode-Opcodes.DCONST_0);
        return null;
    }
    private static boolean scalar(Object value){return value instanceof String||value instanceof Integer||value instanceof Long||value instanceof Float||value instanceof Double;}
    public static List<Constant> constants(byte[] data) {
        ClassNode node=node(data);List<Constant> result=new ArrayList<>();
        for(int field=0;field<node.fields.size();field++) {
            FieldNode value=node.fields.get(field);if(scalar(value.value))result.add(new Constant("f:"+field,value.value.getClass().getSimpleName(),value.value,"field "+value.name));
        }
        for(int method=0;method<node.methods.size();method++) {
            MethodNode value=node.methods.get(method);int index=0;
            for(AbstractInsnNode instruction:value.instructions) {
                Object constant=constant(instruction);
                if(constant!=null)result.add(new Constant("m:"+method+":"+index,constant.getClass().getSimpleName(),constant,value.name+value.desc));
                index++;
            }
        }
        return List.copyOf(result);
    }
    public static byte[] replaceConstant(byte[] data,String key,String text,boolean all) {
        Constant selected=constants(data).stream().filter(row->row.key().equals(key)).findFirst().orElseThrow(()->new IllegalArgumentException("Hằng số đã thay đổi. Hãy tải lại tab."));
        java.util.Map<String,String> changes=new java.util.LinkedHashMap<>();
        for(Constant row:constants(data))if(row.key().equals(key)||(all&&row.type().equals(selected.type())&&Objects.equals(row.value(),selected.value())))changes.put(row.key(),text);
        return replaceConstants(data,changes);
    }
    private static Object parse(Constant selected,String text) {
        Object replacement;
        try {
            replacement=switch(selected.type()) {
                case "String" -> text;
                case "Integer" -> Integer.decode(text.trim());
                case "Long" -> Long.decode(text.trim().replaceFirst("[lL]$",""));
                case "Float" -> Float.parseFloat(text.trim());
                case "Double" -> Double.parseDouble(text.trim());
                default -> throw new IllegalArgumentException("Loại hằng số chưa hỗ trợ.");
            };
        }catch(NumberFormatException error){throw new IllegalArgumentException("Giá trị không đúng kiểu "+selected.type()+".");}
        return replacement;
    }
    public static byte[] replaceConstants(byte[] data,java.util.Map<String,String> changes) {
        ClassNode node=node(data);int changed=0;
        java.util.Map<String,Constant> originals=new java.util.HashMap<>();for(Constant row:constants(data))originals.put(row.key(),row);
        for(var change:changes.entrySet()) {
            Constant row=originals.get(change.getKey());if(row==null)throw new IllegalArgumentException("Vị trí hằng số đã thay đổi; tải lại class.");
            Object replacement=parse(row,change.getValue());
            String[] parts=row.key().split(":");
            if(parts[0].equals("f"))node.fields.get(Integer.parseInt(parts[1])).value=replacement;
            else {
                MethodNode method=node.methods.get(Integer.parseInt(parts[1]));AbstractInsnNode old=method.instructions.get(Integer.parseInt(parts[2]));
                method.instructions.set(old,new LdcInsnNode(replacement));
            }
            changed++;
        }
        if(changed==0)throw new IllegalArgumentException("Không tìm thấy hằng số để sửa.");
        ClassWriter output=new ClassWriter(0);node.accept(output);return output.toByteArray();
    }
    public static byte[] defaultReturn(byte[] data,String name,String descriptor) {
        ClassNode node=node(data);MethodNode method=node.methods.stream().filter(value->value.name.equals(name)&&value.desc.equals(descriptor)).findFirst().orElseThrow();
        if(name.startsWith("<")||(method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0)throw new IllegalArgumentException("Không dùng thao tác này cho constructor hoặc hàm abstract/native.");
        method.instructions.clear();method.tryCatchBlocks.clear();if(method.localVariables!=null)method.localVariables.clear();
        method.visibleLocalVariableAnnotations=null;method.invisibleLocalVariableAnnotations=null;
        Type type=Type.getReturnType(descriptor);
        switch(type.getSort()) {
            case Type.VOID -> method.instructions.add(new InsnNode(Opcodes.RETURN));
            case Type.LONG -> {method.instructions.add(new InsnNode(Opcodes.LCONST_0));method.instructions.add(new InsnNode(Opcodes.LRETURN));}
            case Type.FLOAT -> {method.instructions.add(new InsnNode(Opcodes.FCONST_0));method.instructions.add(new InsnNode(Opcodes.FRETURN));}
            case Type.DOUBLE -> {method.instructions.add(new InsnNode(Opcodes.DCONST_0));method.instructions.add(new InsnNode(Opcodes.DRETURN));}
            case Type.OBJECT,Type.ARRAY -> {method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));method.instructions.add(new InsnNode(Opcodes.ARETURN));}
            default -> {method.instructions.add(new InsnNode(Opcodes.ICONST_0));method.instructions.add(new InsnNode(Opcodes.IRETURN));}
        }
        ClassWriter output=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(output);return output.toByteArray();
    }
    public static String compilationEntry(JarSession session,String entry) throws java.io.IOException {
        if(!entry.endsWith(".class"))return entry;
        java.util.Set<String> names=new java.util.HashSet<>();for(JarSession.Entry value:session.entries())names.add(value.name());
        java.util.Set<String> visited=new java.util.HashSet<>();String current=entry;
        while(visited.add(current)&&visited.size()<32) {
            ClassNode type=new ClassNode();new ClassReader(session.read(current)).accept(type,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            String owner=type.outerClass;
            if(owner==null)for(var nested:type.innerClasses)if(type.name.equals(nested.name)&&nested.outerName!=null){owner=nested.outerName;break;}
            if(owner==null||!current.endsWith(type.name+".class"))break;
            String next=current.substring(0,current.length()-(type.name+".class").length())+owner+".class";
            if(!names.contains(next))break;current=next;
        }
        return current;
    }
    public static String safeInternalName(String name) {
        JarSession.validateEntry(name);
        for(String part:name.split("/")) {
            if(part.isEmpty()||!Character.isJavaIdentifierStart(part.charAt(0)))throw new IllegalArgumentException("Tên class bị làm rối và không thể biên dịch trực tiếp bằng Java. Có thể dùng tab Hằng số.");
            for(int i=1;i<part.length();i++)if(!Character.isJavaIdentifierPart(part.charAt(i)))throw new IllegalArgumentException("Tên class không hợp lệ với Java source. Có thể dùng tab Hằng số.");
        }
        return name;
    }
}
