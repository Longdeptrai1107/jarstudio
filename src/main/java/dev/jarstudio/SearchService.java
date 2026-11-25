package dev.jarstudio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class SearchService {
    public record Hit(JarSession session,String entry,String kind,String member,String detail){}
    public List<Hit> search(List<JarSession> sessions,String query,int limit) throws Exception {
        String term=query.toLowerCase(Locale.ROOT);List<Hit> hits=new ArrayList<>();
        if(term.isBlank())return hits;
        for(JarSession session:sessions)for(JarSession.Entry entry:session.entries()) {
            if(Thread.currentThread().isInterrupted())return hits;
            if(!entry.isClass())continue;
            if(entry.name().toLowerCase(Locale.ROOT).contains(term))hits.add(new Hit(session,entry.name(),"Class","",entry.name()));
            try {
                ClassNode node=ClassTools.node(session.read(entry.name()));
                for(var field:node.fields)if(field.name.toLowerCase(Locale.ROOT).contains(term))hits.add(new Hit(session,entry.name(),"Field",field.name,field.desc));
                for(var method:node.methods) {
                    if(method.name.toLowerCase(Locale.ROOT).contains(term))hits.add(new Hit(session,entry.name(),"Method",method.name,method.desc));
                    for(var instruction:method.instructions)if(instruction instanceof LdcInsnNode constant&&constant.cst instanceof String text&&text.toLowerCase(Locale.ROOT).contains(term))
                        hits.add(new Hit(session,entry.name(),"String",method.name,text));
                }
            }catch(IllegalArgumentException ignored){}
            if(hits.size()>=limit)return hits.subList(0,limit);
        }
        return hits;
    }
    public List<Hit> references(List<JarSession> sessions,String owner,ClassTools.Member target) throws Exception {
        List<Hit> hits=new ArrayList<>();
        for(JarSession session:sessions)for(JarSession.Entry entry:session.entries())if(entry.isClass()) {
            if(Thread.currentThread().isInterrupted())return hits;
            try {
                ClassNode node=ClassTools.node(session.read(entry.name()));
                for(var method:node.methods)for(var instruction:method.instructions) {
                    boolean match=target==null?instruction instanceof TypeInsnNode type&&type.desc.equals(owner)
                        :target.method()?instruction instanceof MethodInsnNode call&&call.owner.equals(owner)&&call.name.equals(target.name())&&call.desc.equals(target.descriptor())
                        :instruction instanceof FieldInsnNode field&&field.owner.equals(owner)&&field.name.equals(target.name())&&field.desc.equals(target.descriptor());
                    if(match)hits.add(new Hit(session,entry.name(),"Reference",method.name,method.desc+" → "+owner.replace('/','.')+(target==null?"":"."+target.name())));
                }
            }catch(IllegalArgumentException ignored){}
        }
        return hits;
    }
}
