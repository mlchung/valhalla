/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @summary Test constant APIs for inline classes
 * @run testng/othervm test.ConstableTest
 */

package test;

import java.lang.constant.*;
import java.lang.constant.DirectMethodHandleDesc.Kind;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static java.lang.invoke.MethodType.methodType;
import static java.lang.invoke.MethodHandleInfo.*;

import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

public class ConstableTest {
    static final String VALUE_DESCRIPTOR = "Qtest/ConstableTest$Value;";
    static final String VALUE_REF_DESCRIPTOR = "Ltest/ConstableTest$Value$ref;";

    static inline class Value {
        int x;
        Value(int x) {
            this.x = x;
        }
        static Value toVal(Value.ref v) {
            return (Value) v;
        }
        static Value.ref toRef(Value v) {
            return v;
        }
    }

    static interface I {
        default Value toValue(int x) {
            return new Value(x);
        }
        default Value.ref toValueRef(int x) {
            return new Value(x);
        }
    }

    static class Foo implements I {
        Value v;
        Foo(int x) {
            this.v = new Value(x);
        }
        @Override
        public Value.ref toValueRef(int x) {
            return new Value(x+10);
        }
    }

    @DataProvider
    static Object[][] classDescriptors() {
        return new Object[][]{
            new Object[] { ConstableTest.class, "Ltest/ConstableTest;" },
            new Object[] { Value.class, VALUE_DESCRIPTOR },
            new Object[] { Value.ref.class, VALUE_REF_DESCRIPTOR },
            new Object[] { Value[][].class, "[[" + VALUE_DESCRIPTOR },
            new Object[] { Value.ref[][][].class, "[[[" + VALUE_REF_DESCRIPTOR }
        };
    }

    @Test(dataProvider = "classDescriptors")
    public static void testClassDescriptors(Class<?> c, String descriptor) throws Exception {
        ClassDesc desc1 = c.isInlineClass() ? ClassDesc.ofInlineClass(c.getName())
                                            : (c.isArray() ? toArrayClassDesc(c)
                                                           : ClassDesc.of(c.getName()));
        ClassDesc desc2 = ClassDesc.ofDescriptor(descriptor);
        ClassDesc desc = c.describeConstable().get();
        assertEquals(c.descriptorString(), descriptor);
        assertEquals(desc.descriptorString(), descriptor);
        assertEquals(desc, desc1);
        assertEquals(desc, desc2);
        assertTrue(c.isInlineClass() == desc.isInlineClass());

        MethodHandles.Lookup lookup = MethodHandles.lookup();
        assertTrue(desc.resolveConstantDesc(lookup) == c);
    }

    private static ClassDesc toArrayClassDesc(Class<?> c) {
        Class<?> elementType = c.getComponentType();
        int dims=1;
        while (elementType.isArray()) {
            elementType = elementType.getComponentType();
            dims++;
        }
        return elementType.describeConstable().get().arrayType(dims);
    }

    @DataProvider
    static Object[][] methodTypeDescriptors() {
        return new Object[][]{
            new Object[] { methodType(Value.class, Value.ref.class), "(" + VALUE_REF_DESCRIPTOR + ")" + VALUE_DESCRIPTOR },
            new Object[] { methodType(Value.ref.class, Value.class), "(" + VALUE_DESCRIPTOR + ")" + VALUE_REF_DESCRIPTOR },
        };
    }

    @Test(dataProvider = "methodTypeDescriptors")
    public static void testMethodTypeDescriptors(MethodType methodType, String descriptor) {
        MethodTypeDesc desc1 = MethodTypeDesc.ofDescriptor(descriptor);
        MethodTypeDesc desc2 = MethodTypeDesc.of(methodType.returnType().describeConstable().get(),
                                                 toParamClassDescs(methodType));
        MethodTypeDesc desc = methodType.describeConstable().get();
        assertEquals(methodType.descriptorString(), descriptor);
        assertEquals(desc.descriptorString(), descriptor);
        assertEquals(desc, desc1);
        assertEquals(desc, desc2);
    }

    private static ClassDesc[] toParamClassDescs(MethodType methodType) {
        List<ClassDesc> list = new ArrayList<>();
        for (Class<?> param : methodType.parameterArray()) {
            list.add(param.describeConstable().get());
        }
        return list.stream().toArray(ClassDesc[]::new);
    }

    @DataProvider
    static Object[][] methodHandles() {
        // arguments for method handle invocation
        Object[] v1 = new Object[] { new Value(10), new Value(10) };
        Object[] foo = new Object[] { new Foo(50), new Value(50) };
        Object[] fooV1 = new Object[] { new Foo(10), Integer.valueOf(20), new Value(20) };
        Object[] fooV2 = new Object[] { new Foo(10), Integer.valueOf(30), new Value(40) };
        Object[] dummy = new Object[2];
        return new Object[][]{
            new Object[] { REF_invokeStatic, Value.class, "toVal", methodType(Value.class, Value.ref.class), v1 },
            new Object[] { REF_invokeStatic, Value.class, "toRef", methodType(Value.ref.class, Value.class), v1 },
            new Object[] { REF_invokeInterface, I.class, "toValue", methodType(Value.class, int.class), dummy },
            new Object[] { REF_invokeInterface, I.class, "toValueRef", methodType(Value.ref.class, int.class), dummy },
            new Object[] { REF_getField, Foo.class, "v", methodType(Value.class), foo },
            new Object[] { REF_invokeVirtual, Foo.class, "toValue", methodType(Value.class, int.class), fooV1 },
            new Object[] { REF_invokeVirtual, Foo.class, "toValueRef", methodType(Value.ref.class, int.class), fooV2 },
        };
    }

    @Test(dataProvider = "methodHandles")
    public static void testMethodHandleDesc(int refKind, Class<?> decl, String name, MethodType mtype, Object[] args) throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        ClassDesc owner = decl.describeConstable().get();
        MethodHandle mh;
        DirectMethodHandleDesc desc;
        switch (refKind) {
            case REF_invokeStatic:
                mh = lookup.findStatic(decl, name, mtype);
                desc = MethodHandleDesc.of(Kind.STATIC, owner, name, mtype.descriptorString());
                break;
            case REF_invokeInterface:
                mh = lookup.findVirtual(decl, name, mtype);
                desc = MethodHandleDesc.of(Kind.INTERFACE_VIRTUAL, owner, name, mtype.descriptorString());
                break;
            case REF_invokeVirtual:
                mh = lookup.findVirtual(decl, name, mtype);
                desc = MethodHandleDesc.of(Kind.VIRTUAL, owner, name, mtype.descriptorString());
                break;
            case REF_getField:
                mh = lookup.findGetter(decl, name, mtype.returnType());
                desc = MethodHandleDesc.ofField(Kind.GETTER, owner, name, mtype.returnType().describeConstable().get());
                break;
            default:
                throw new UnsupportedOperationException("reference kind " + refKind);
        }
        assertEquals(mh.describeConstable().get(), desc);

        // invoke the method handle
        if (refKind == REF_invokeStatic) {
            assertEquals(mh.invoke(args[0]), args[1]);
        } else if (refKind == REF_invokeVirtual) {
            assertEquals(mh.invoke(args[0], args[1]), args[2]);
        } else if (refKind == REF_getField) {
            assertEquals(mh.invoke(args[0]), args[1]);
        }
    }
}
