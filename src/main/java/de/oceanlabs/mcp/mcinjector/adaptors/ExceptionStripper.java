package de.oceanlabs.mcp.mcinjector.adaptors;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import de.oceanlabs.mcp.mcinjector.data.Exceptions;

public class ExceptionStripper extends ClassVisitor {

    String className;

    public ExceptionStripper(ClassVisitor cn)
    {
        super(Opcodes.ASM6, cn);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
    {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
    {
        // Only strip the exceptions present on the class if we have exceptions to add
        boolean hasExceptions = Exceptions.INSTANCE.getExceptions(className, name, desc).length != 0;
        return super.visitMethod(access, name, desc, signature, hasExceptions ? new String[0] : exceptions);
    }

}
