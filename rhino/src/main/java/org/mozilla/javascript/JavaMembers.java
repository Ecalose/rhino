/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.javascript;

import static java.lang.reflect.Modifier.isProtected;
import static java.lang.reflect.Modifier.isPublic;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessControlContext;
import java.security.AllPermission;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.mozilla.javascript.lc.type.TypeInfo;
import org.mozilla.javascript.lc.type.TypeInfoFactory;

/**
 * @author Mike Shaver
 * @author Norris Boyd
 * @see NativeJavaObject
 * @see NativeJavaClass
 */
class JavaMembers {

    private static final boolean STRICT_REFLECTIVE_ACCESS = isModularJava();

    private static final Permission allPermission = new AllPermission();

    JavaMembers(Scriptable scope, Class<?> cl) {
        this(scope, cl, false);
    }

    JavaMembers(Scriptable scope, Class<?> cl, boolean includeProtected) {
        try (Context cx = ContextFactory.getGlobal().enterContext()) {
            ClassShutter shutter = cx.getClassShutter();
            if (shutter != null && !shutter.visibleToScripts(cl.getName())) {
                throw Context.reportRuntimeErrorById("msg.access.prohibited", cl.getName());
            }
            this.members = new HashMap<>();
            this.staticMembers = new HashMap<>();
            this.cl = cl;
            boolean includePrivate = cx.hasFeature(Context.FEATURE_ENHANCED_JAVA_ACCESS);
            reflect(cx, scope, includeProtected, includePrivate);
        }
    }

    /**
     * This method returns true if we are on a "modular" version of Java (Java 11 or up). It does
     * not use the SourceVersion class because this is not present on Android.
     */
    private static boolean isModularJava() {
        try {
            Class.class.getMethod("getModule");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    boolean has(String name, boolean isStatic) {
        Map<String, Object> ht = isStatic ? staticMembers : members;
        Object obj = ht.get(name);
        if (obj != null) {
            return true;
        }
        return findExplicitFunction(name, isStatic) != null;
    }

    Object get(Scriptable scope, String name, Object javaObject, boolean isStatic) {
        Map<String, Object> ht = isStatic ? staticMembers : members;
        Object member = ht.get(name);
        if (!isStatic && member == null) {
            // Try to get static member from instance (LC3)
            member = staticMembers.get(name);
        }
        if (member == null) {
            member =
                    this.getExplicitFunction(
                            scope, name,
                            javaObject, isStatic);
            if (member == null) return Scriptable.NOT_FOUND;
        }
        if (member instanceof Scriptable) {
            return member;
        }
        Context cx = Context.getContext();
        Object rval;
        Class<?> type;
        try {
            if (member instanceof BeanProperty) {
                BeanProperty bp = (BeanProperty) member;
                if (bp.getter == null) return Scriptable.NOT_FOUND;
                rval = bp.getter.invoke(javaObject, ScriptRuntime.emptyArgs);
                type = bp.getter.getReturnType().asClass();
            } else {
                Field field = (Field) member;
                rval = field.get(isStatic ? null : javaObject);
                type = field.getType();
            }
        } catch (Exception ex) {
            throw Context.throwAsScriptRuntimeEx(ex);
        }
        // Need to wrap the object before we return it.
        scope = ScriptableObject.getTopLevelScope(scope);
        return cx.getWrapFactory().wrap(cx, scope, rval, type);
    }

    void put(Scriptable scope, String name, Object javaObject, Object value, boolean isStatic) {
        Map<String, Object> ht = isStatic ? staticMembers : members;
        Object member = ht.get(name);
        if (!isStatic && member == null) {
            // Try to get static member from instance (LC3)
            member = staticMembers.get(name);
        }
        if (member == null) throw reportMemberNotFound(name);
        if (member instanceof FieldAndMethods) {
            FieldAndMethods fam = (FieldAndMethods) ht.get(name);
            member = fam.field;
        }

        // Is this a bean property "set"?
        if (member instanceof BeanProperty) {
            BeanProperty bp = (BeanProperty) member;
            if (bp.setter == null) {
                throw reportMemberNotFound(name);
            }
            // If there's only one setter or if the value is null, use the
            // main setter. Otherwise, let the NativeJavaMethod decide which
            // setter to use:
            if (bp.setters == null || value == null) {
                var setType = bp.setter.getArgTypes().get(0);
                Object[] args = {Context.jsToJava(value, setType)};
                try {
                    bp.setter.invoke(javaObject, args);
                } catch (Exception ex) {
                    throw Context.throwAsScriptRuntimeEx(ex);
                }
            } else {
                Object[] args = {value};
                bp.setters.call(
                        Context.getContext(),
                        ScriptableObject.getTopLevelScope(scope),
                        scope,
                        args);
            }
        } else {
            if (!(member instanceof Field)) {
                String str =
                        (member == null) ? "msg.java.internal.private" : "msg.java.method.assign";
                throw Context.reportRuntimeErrorById(str, name);
            }
            Field field = (Field) member;
            Object javaValue = Context.jsToJava(value, field.getType());
            try {
                field.set(javaObject, javaValue);
            } catch (IllegalAccessException accessEx) {
                if ((field.getModifiers() & Modifier.FINAL) != 0) {
                    // treat Java final the same as JavaScript [[READONLY]]
                    return;
                }
                throw Context.throwAsScriptRuntimeEx(accessEx);
            } catch (IllegalArgumentException argEx) {
                throw Context.reportRuntimeErrorById(
                        "msg.java.internal.field.type",
                        value.getClass().getName(),
                        field,
                        javaObject.getClass().getName());
            }
        }
    }

    Object[] getIds(boolean isStatic) {
        Map<String, Object> map = isStatic ? staticMembers : members;
        return map.keySet().toArray(new Object[0]);
    }

    static String javaSignature(Class<?> type) {
        if (!type.isArray()) {
            return type.getName();
        }
        int arrayDimension = 0;
        do {
            ++arrayDimension;
            type = type.getComponentType();
        } while (type.isArray());
        String name = type.getName();
        String suffix = "[]";
        if (arrayDimension == 1) {
            return name.concat(suffix);
        }
        int length = name.length() + arrayDimension * suffix.length();
        StringBuilder sb = new StringBuilder(length);
        sb.append(name);
        while (arrayDimension != 0) {
            --arrayDimension;
            sb.append(suffix);
        }
        return sb.toString();
    }

    static String liveConnectSignature(List<TypeInfo> argTypes) {
        if (argTypes.isEmpty()) {
            return "()";
        }

        var builder = new StringBuilder();

        builder.append('(');
        var iter = argTypes.iterator();
        if (iter.hasNext()) {
            builder.append(javaSignature(iter.next().asClass()));
            while (iter.hasNext()) {
                builder.append(',').append(javaSignature(iter.next().asClass()));
            }
        }
        builder.append(')');

        return builder.toString();
    }

    private MemberBox findExplicitFunction(String name, boolean isStatic) {
        int sigStart = name.indexOf('(');
        if (sigStart < 0) {
            return null;
        }

        Map<String, Object> ht = isStatic ? staticMembers : members;
        MemberBox[] methodsOrCtors = null;
        boolean isCtor = (isStatic && sigStart == 0);

        if (isCtor) {
            // Explicit request for an overloaded constructor
            methodsOrCtors = ctors.methods;
        } else {
            // Explicit request for an overloaded method
            String trueName = name.substring(0, sigStart);
            Object obj = ht.get(trueName);
            if (!isStatic && obj == null) {
                // Try to get static member from instance (LC3)
                obj = staticMembers.get(trueName);
            }
            if (obj instanceof NativeJavaMethod) {
                NativeJavaMethod njm = (NativeJavaMethod) obj;
                methodsOrCtors = njm.methods;
            }
        }

        if (methodsOrCtors != null) {
            for (MemberBox methodsOrCtor : methodsOrCtors) {
                String sig = liveConnectSignature(methodsOrCtor.getArgTypes());
                if (sigStart + sig.length() == name.length()
                        && name.regionMatches(sigStart, sig, 0, sig.length())) {
                    return methodsOrCtor;
                }
            }
        }

        return null;
    }

    private Object getExplicitFunction(
            Scriptable scope, String name, Object javaObject, boolean isStatic) {
        Map<String, Object> ht = isStatic ? staticMembers : members;
        Object member = null;
        MemberBox methodOrCtor = findExplicitFunction(name, isStatic);

        if (methodOrCtor != null) {
            Scriptable prototype = ScriptableObject.getFunctionPrototype(scope);

            if (methodOrCtor.isCtor()) {
                NativeJavaConstructor fun = new NativeJavaConstructor(methodOrCtor);
                fun.setPrototype(prototype);
                member = fun;
                ht.put(name, fun);
            } else {
                String trueName = methodOrCtor.getName();
                member = ht.get(trueName);

                if (member instanceof NativeJavaMethod
                        && ((NativeJavaMethod) member).methods.length > 1) {
                    NativeJavaMethod fun = new NativeJavaMethod(methodOrCtor, name);
                    fun.setPrototype(prototype);
                    ht.put(name, fun);
                    member = fun;
                }
            }
        }

        return member;
    }

    /**
     * Retrieves mapping of methods to accessible methods for a class. In case the class is not
     * public, retrieves methods with same signature as its public methods from public superclasses
     * and interfaces (if they exist). Basically upcasts every method to the nearest accessible
     * method.
     */
    private Method[] discoverAccessibleMethods(
            Class<?> clazz, boolean includeProtected, boolean includePrivate) {
        Map<MethodSignature, Method> map = new HashMap<>();
        discoverAccessibleMethods(clazz, map, includeProtected, includePrivate);
        return map.values().toArray(new Method[0]);
    }

    @SuppressWarnings("deprecation")
    private void discoverAccessibleMethods(
            Class<?> clazz,
            Map<MethodSignature, Method> map,
            boolean includeProtected,
            boolean includePrivate) {
        if (isPublic(clazz.getModifiers()) || includePrivate) {
            try {
                if (includeProtected || includePrivate) {
                    while (clazz != null) {
                        try {
                            Method[] methods = clazz.getDeclaredMethods();
                            for (Method method : methods) {
                                int mods = method.getModifiers();

                                if (isPublic(mods) || isProtected(mods) || includePrivate) {
                                    Method registered = registerMethod(map, method);
                                    // We don't want to replace the deprecated method here
                                    // because it is not available on Android.
                                    if (includePrivate && !registered.isAccessible()) {
                                        registered.setAccessible(true);
                                    }
                                }
                            }
                            Class<?>[] interfaces = clazz.getInterfaces();
                            for (Class<?> intface : interfaces) {
                                discoverAccessibleMethods(
                                        intface, map, includeProtected, includePrivate);
                            }
                            clazz = clazz.getSuperclass();
                        } catch (SecurityException e) {
                            // Some security settings (i.e., applets) disallow
                            // access to Class.getDeclaredMethods. Fall back to
                            // Class.getMethods.
                            discoverPublicMethods(clazz, map);
                            break; // getMethods gets superclass methods, no
                            // need to loop any more
                        }
                    }
                } else {
                    discoverPublicMethods(clazz, map);
                }
                return;
            } catch (SecurityException e) {
                Context.reportWarning(
                        "Could not discover accessible methods of class "
                                + clazz.getName()
                                + " due to lack of privileges, "
                                + "attemping superclasses/interfaces.");
                // Fall through and attempt to discover superclass/interface
                // methods
            }
        }

        Class<?>[] interfaces = clazz.getInterfaces();
        for (Class<?> intface : interfaces) {
            discoverAccessibleMethods(intface, map, includeProtected, includePrivate);
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            discoverAccessibleMethods(superclass, map, includeProtected, includePrivate);
        }
    }

    void discoverPublicMethods(Class<?> clazz, Map<MethodSignature, Method> map) {
        Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            registerMethod(map, method);
        }
    }

    static Method registerMethod(Map<MethodSignature, Method> map, Method method) {
        MethodSignature sig = new MethodSignature(method);
        // Array may contain methods with same parameter signature but different return value!
        // (which is allowed in bytecode, but not in JLS) we will take the best method
        return map.merge(sig, method, JavaMembers::getMoreConcreteMethod);
    }

    private static Method getMoreConcreteMethod(Method oldValue, Method newValue) {
        if (oldValue.getReturnType().equals(newValue.getReturnType())) {
            return oldValue; // same return type. Do not overwrite existing method
        } else if (oldValue.getReturnType().isAssignableFrom(newValue.getReturnType())) {
            return newValue; // more concrete return type. Replace method
        } else {
            return oldValue;
        }
    }

    static final class MethodSignature {
        private final String name;
        private final Class<?>[] args;

        private MethodSignature(String name, Class<?>[] args) {
            this.name = name;
            this.args = args;
        }

        MethodSignature(Method method) {
            this(method.getName(), method.getParameterTypes());
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof MethodSignature) {
                MethodSignature ms = (MethodSignature) o;
                return ms.name.equals(name) && Arrays.equals(args, ms.args);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return name.hashCode() ^ args.length;
        }
    }

    @SuppressWarnings("unchecked")
    private void reflect(
            Context cx, Scriptable scope, boolean includeProtected, boolean includePrivate) {
        // We reflect methods first, because we want overloaded field/method
        // names to be allocated to the NativeJavaMethod before the field
        // gets in the way.
        var typeFactory = TypeInfoFactory.get(scope);

        Method[] methods = discoverAccessibleMethods(cl, includeProtected, includePrivate);
        for (Method method : methods) {
            int mods = method.getModifiers();
            boolean isStatic = Modifier.isStatic(mods);
            Map<String, Object> ht = isStatic ? staticMembers : members;
            String name = method.getName();
            Object value = ht.get(name);
            if (value == null) {
                ht.put(name, method);
            } else {
                ArrayList<Object> overloadedMethods;
                if (value instanceof ArrayList) {
                    overloadedMethods = (ArrayList<Object>) value;
                } else {
                    if (!(value instanceof Method)) Kit.codeBug();
                    // value should be instance of Method as at this stage
                    // staticMembers and members can only contain methods
                    overloadedMethods = new ArrayList<>();
                    overloadedMethods.add(value);
                    ht.put(name, overloadedMethods);
                }
                overloadedMethods.add(method);
            }
        }

        // replace Method instances by wrapped NativeJavaMethod objects
        // first in staticMembers and then in members
        for (int tableCursor = 0; tableCursor != 2; ++tableCursor) {
            boolean isStatic = (tableCursor == 0);
            Map<String, Object> ht = isStatic ? staticMembers : members;
            for (Map.Entry<String, Object> entry : ht.entrySet()) {
                MemberBox[] methodBoxes;
                Object value = entry.getValue();
                if (value instanceof Method) {
                    methodBoxes = new MemberBox[1];
                    methodBoxes[0] = new MemberBox((Method) value, typeFactory);
                } else {
                    ArrayList<Object> overloadedMethods = (ArrayList<Object>) value;
                    int N = overloadedMethods.size();
                    if (N < 2) Kit.codeBug();
                    methodBoxes = new MemberBox[N];
                    for (int i = 0; i != N; ++i) {
                        Method method = (Method) overloadedMethods.get(i);
                        methodBoxes[i] = new MemberBox(method, typeFactory);
                    }
                }
                NativeJavaMethod fun = new NativeJavaMethod(methodBoxes);
                if (scope != null) {
                    ScriptRuntime.setFunctionProtoAndParent(fun, cx, scope, false);
                }
                entry.setValue(fun);
            }
        }

        // Reflect fields.
        Field[] fields = getAccessibleFields(includeProtected, includePrivate);
        for (Field field : fields) {
            String name = field.getName();
            int mods = field.getModifiers();
            try {
                boolean isStatic = Modifier.isStatic(mods);
                Map<String, Object> ht = isStatic ? staticMembers : members;
                Object member = ht.get(name);
                if (member == null) {
                    ht.put(name, field);
                } else if (member instanceof NativeJavaMethod) {
                    NativeJavaMethod method = (NativeJavaMethod) member;
                    FieldAndMethods fam = new FieldAndMethods(scope, method.methods, field);
                    Map<String, FieldAndMethods> fmht =
                            isStatic ? staticFieldAndMethods : fieldAndMethods;
                    if (fmht == null) {
                        fmht = new HashMap<>();
                        if (isStatic) {
                            staticFieldAndMethods = fmht;
                        } else {
                            fieldAndMethods = fmht;
                        }
                    }
                    fmht.put(name, fam);
                    ht.put(name, fam);
                } else if (member instanceof Field) {
                    Field oldField = (Field) member;
                    // If this newly reflected field shadows an inherited field,
                    // then replace it. Otherwise, since access to the field
                    // would be ambiguous from Java, no field should be
                    // reflected.
                    // For now, the first field found wins, unless another field
                    // explicitly shadows it.
                    if (oldField.getDeclaringClass().isAssignableFrom(field.getDeclaringClass())) {
                        ht.put(name, field);
                    }
                } else {
                    // "unknown member type"
                    Kit.codeBug();
                }
            } catch (SecurityException e) {
                // skip this field
                Context.reportWarning(
                        "Could not access field "
                                + name
                                + " of class "
                                + cl.getName()
                                + " due to lack of privileges.");
            }
        }

        // Create bean properties from corresponding get/set methods first for
        // static members and then for instance members
        for (int tableCursor = 0; tableCursor != 2; ++tableCursor) {
            boolean isStatic = (tableCursor == 0);
            Map<String, Object> ht = isStatic ? staticMembers : members;

            Map<String, BeanProperty> toAdd = new HashMap<>();

            // Now, For each member, make "bean" properties.
            for (String name : ht.keySet()) {
                // Is this a getter?
                boolean memberIsGetMethod = name.startsWith("get");
                boolean memberIsSetMethod = name.startsWith("set");
                boolean memberIsIsMethod = name.startsWith("is");
                if (memberIsGetMethod || memberIsIsMethod || memberIsSetMethod) {
                    // Double check name component.
                    String nameComponent = name.substring(memberIsIsMethod ? 2 : 3);
                    if (nameComponent.length() == 0) continue;

                    // Make the bean property name.
                    String beanPropertyName = nameComponent;
                    char ch0 = nameComponent.charAt(0);
                    if (Character.isUpperCase(ch0)) {
                        if (nameComponent.length() == 1) {
                            beanPropertyName = nameComponent.toLowerCase(Locale.ROOT);
                        } else {
                            char ch1 = nameComponent.charAt(1);
                            if (!Character.isUpperCase(ch1)) {
                                beanPropertyName =
                                        Character.toLowerCase(ch0) + nameComponent.substring(1);
                            }
                        }
                    }

                    // If we already have a member by this name, don't do this
                    // property.
                    if (toAdd.containsKey(beanPropertyName)) continue;
                    Object v = ht.get(beanPropertyName);
                    if (v != null) {
                        // A private field shouldn't mask a public getter/setter
                        if (!includePrivate
                                || !(v instanceof Member)
                                || !Modifier.isPrivate(((Member) v).getModifiers())) {

                            continue;
                        }
                    }

                    // Find the getter method, or if there is none, the is-
                    // method.
                    MemberBox getter = null;
                    getter = findGetter(isStatic, ht, "get", nameComponent);
                    // If there was no valid getter, check for an is- method.
                    if (getter == null) {
                        getter = findGetter(isStatic, ht, "is", nameComponent);
                    }

                    // setter
                    MemberBox setter = null;
                    NativeJavaMethod setters = null;
                    String setterName = "set".concat(nameComponent);

                    // Is this value a method?
                    Object member = ht.get(setterName);
                    if (member instanceof NativeJavaMethod) {
                        NativeJavaMethod njmSet = (NativeJavaMethod) member;
                        if (getter != null) {
                            // We have a getter. Now, do we have a matching
                            // setter?
                            var type = getter.getReturnType();
                            setter = extractSetMethod(type, njmSet.methods, isStatic);
                        } else {
                            // No getter, find any set method
                            setter = extractSetMethod(njmSet.methods, isStatic);
                        }
                        if (njmSet.methods.length > 1) {
                            setters = njmSet;
                        }
                    }
                    // Make the property.
                    BeanProperty bp = new BeanProperty(getter, setter, setters);
                    toAdd.put(beanPropertyName, bp);
                }
            }

            // Add the new bean properties.
            ht.putAll(toAdd);
        }

        // Reflect constructors
        Constructor<?>[] constructors = getAccessibleConstructors(includePrivate);
        MemberBox[] ctorMembers = new MemberBox[constructors.length];
        for (int i = 0; i != constructors.length; ++i) {
            ctorMembers[i] = new MemberBox(constructors[i], typeFactory);
        }
        ctors = new NativeJavaMethod(ctorMembers, cl.getSimpleName());
    }

    private Constructor<?>[] getAccessibleConstructors(boolean includePrivate) {
        // The JVM currently doesn't allow changing access on java.lang.Class
        // constructors, so don't try
        if (includePrivate && cl != ScriptRuntime.ClassClass) {
            try {
                Constructor<?>[] cons = cl.getDeclaredConstructors();
                AccessibleObject.setAccessible(cons, true);

                return cons;
            } catch (SecurityException e) {
                // Fall through to !includePrivate case
                Context.reportWarning(
                        "Could not access constructor "
                                + " of class "
                                + cl.getName()
                                + " due to lack of privileges.");
            }
        }
        return cl.getConstructors();
    }

    @SuppressWarnings("deprecation")
    private Field[] getAccessibleFields(boolean includeProtected, boolean includePrivate) {
        if (includePrivate || includeProtected) {
            try {
                List<Field> fieldsList = new ArrayList<>();
                Class<?> currentClass = cl;

                while (currentClass != null) {
                    // get all declared fields in this class, make them
                    // accessible, and save
                    Field[] declared = currentClass.getDeclaredFields();
                    for (Field field : declared) {
                        int mod = field.getModifiers();
                        if (includePrivate || isPublic(mod) || isProtected(mod)) {
                            if (!field.isAccessible()) field.setAccessible(true);
                            fieldsList.add(field);
                        }
                    }
                    // walk up superclass chain.  no need to deal specially with
                    // interfaces, since they can't have fields
                    currentClass = currentClass.getSuperclass();
                }

                return fieldsList.toArray(new Field[0]);
            } catch (SecurityException e) {
                // fall through to !includePrivate case
            }
        }
        return cl.getFields();
    }

    private static MemberBox findGetter(
            boolean isStatic, Map<String, Object> ht, String prefix, String propertyName) {
        String getterName = prefix.concat(propertyName);
        // Check that the getter is a method.
        Object member = ht.get(getterName);
        if (member instanceof NativeJavaMethod) {
            NativeJavaMethod njmGet = (NativeJavaMethod) member;
            return extractGetMethod(njmGet.methods, isStatic);
        }
        return null;
    }

    private static MemberBox extractGetMethod(MemberBox[] methods, boolean isStatic) {
        // Inspect the list of all MemberBox for the only one having no
        // parameters
        for (MemberBox method : methods) {
            // Does getter method have an empty parameter list with a return
            // value (eg. a getSomething() or isSomething())?
            if (method.getArgTypes().isEmpty() && (!isStatic || method.isStatic())) {
                var type = method.getReturnType();
                if (!type.isVoid()) {
                    return method;
                }
                break;
            }
        }
        return null;
    }

    private static MemberBox extractSetMethod(
            TypeInfo type, MemberBox[] methods, boolean isStatic) {
        //
        // Note: it may be preferable to allow NativeJavaMethod.findFunction()
        //       to find the appropriate setter; unfortunately, it requires an
        //       instance of the target arg to determine that.
        //

        MemberBox acceptableMatch = null;
        for (MemberBox method : methods) {
            if (!isStatic || method.isStatic()) {
                var argTypes = method.getArgTypes();
                if (argTypes.size() == 1) {
                    if (type.is(argTypes.get(0).asClass())) {
                        // perfect match, no need to continue scanning
                        return method;
                    }
                    if (acceptableMatch == null
                            && argTypes.get(0).asClass().isAssignableFrom(type.asClass())) {
                        // do not return at this point, there can still be perfect match
                        acceptableMatch = method;
                    }
                }
            }
        }
        return acceptableMatch;
    }

    private static MemberBox extractSetMethod(MemberBox[] methods, boolean isStatic) {

        for (MemberBox method : methods) {
            if (!isStatic || method.isStatic()) {
                if (method.getReturnType().isVoid()) {
                    if (method.getArgTypes().size() == 1) {
                        return method;
                    }
                }
            }
        }
        return null;
    }

    Map<String, FieldAndMethods> getFieldAndMethodsObjects(
            Scriptable scope, Object javaObject, boolean isStatic) {
        Map<String, FieldAndMethods> ht = isStatic ? staticFieldAndMethods : fieldAndMethods;
        if (ht == null) return null;
        int len = ht.size();
        Map<String, FieldAndMethods> result = new HashMap<>(len);
        for (FieldAndMethods fam : ht.values()) {
            FieldAndMethods famNew = new FieldAndMethods(scope, fam.methods, fam.field);
            famNew.javaObject = javaObject;
            result.put(fam.field.getName(), famNew);
        }
        return result;
    }

    static JavaMembers lookupClass(
            Scriptable scope, Class<?> dynamicType, Class<?> staticType, boolean includeProtected) {
        JavaMembers members;
        ClassCache cache = ClassCache.get(scope);
        Map<ClassCache.CacheKey, JavaMembers> ct = cache.getClassCacheMap();

        Class<?> cl = dynamicType;
        Object secCtx = getSecurityContext();
        for (; ; ) {
            members = ct.get(new ClassCache.CacheKey(cl, secCtx));
            if (members != null) {
                if (cl != dynamicType) {
                    // member lookup for the original class failed because of
                    // missing privileges, cache the result so we don't try again
                    ct.put(new ClassCache.CacheKey(dynamicType, secCtx), members);
                }
                return members;
            }
            try {
                members = createJavaMembers(cache.getAssociatedScope(), cl, includeProtected);
                break;
            } catch (SecurityException e) {
                // Reflection may fail for objects that are in a restricted
                // access package (e.g. sun.*).  If we get a security
                // exception, try again with the static type if it is interface.
                // Otherwise, try superclass
                if (staticType != null && staticType.isInterface()) {
                    cl = staticType;
                    staticType = null; // try staticType only once
                } else {
                    Class<?> parent = cl.getSuperclass();
                    if (parent == null) {
                        if (cl.isInterface()) {
                            // last resort after failed staticType interface
                            parent = ScriptRuntime.ObjectClass;
                        } else {
                            throw e;
                        }
                    }
                    cl = parent;
                }
            }
        }

        if (cache.isCachingEnabled()) {
            ct.put(new ClassCache.CacheKey(cl, secCtx), members);
            if (cl != dynamicType) {
                // member lookup for the original class failed because of
                // missing privileges, cache the result so we don't try again
                ct.put(new ClassCache.CacheKey(dynamicType, secCtx), members);
            }
        }
        return members;
    }

    private static JavaMembers createJavaMembers(
            Scriptable associatedScope, Class<?> cl, boolean includeProtected) {
        if (STRICT_REFLECTIVE_ACCESS) {
            return new JavaMembers_jdk11(associatedScope, cl, includeProtected);
        } else {
            return new JavaMembers(associatedScope, cl, includeProtected);
        }
    }

    private static Object getSecurityContext() {
        Object sec = null;
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sec = sm.getSecurityContext();
            if (sec instanceof AccessControlContext) {
                try {
                    ((AccessControlContext) sec).checkPermission(allPermission);
                    // if we have allPermission, we do not need to store the
                    // security object in the cache key
                    return null;
                } catch (SecurityException e) {
                }
            }
        }
        return sec;
    }

    RuntimeException reportMemberNotFound(String memberName) {
        return Context.reportRuntimeErrorById(
                "msg.java.member.not.found", cl.getName(), memberName);
    }

    private Class<?> cl;
    private Map<String, Object> members;
    private Map<String, FieldAndMethods> fieldAndMethods;
    private Map<String, Object> staticMembers;
    private Map<String, FieldAndMethods> staticFieldAndMethods;
    NativeJavaMethod ctors; // we use NativeJavaMethod for ctor overload resolution
}

class BeanProperty {
    BeanProperty(MemberBox getter, MemberBox setter, NativeJavaMethod setters) {
        this.getter = getter;
        this.setter = setter;
        this.setters = setters;
    }

    MemberBox getter;
    MemberBox setter;
    NativeJavaMethod setters;
}

class FieldAndMethods extends NativeJavaMethod {
    private static final long serialVersionUID = -9222428244284796755L;

    FieldAndMethods(Scriptable scope, MemberBox[] methods, Field field) {
        super(methods);
        this.field = field;
        setParentScope(scope);
        setPrototype(ScriptableObject.getFunctionPrototype(scope));
    }

    @Override
    public Object getDefaultValue(Class<?> hint) {
        if (hint == ScriptRuntime.FunctionClass) return this;
        Object rval;
        Class<?> type;
        try {
            rval = field.get(javaObject);
            type = field.getType();
        } catch (IllegalAccessException accEx) {
            throw Context.reportRuntimeErrorById("msg.java.internal.private", field.getName());
        }
        Context cx = Context.getContext();
        rval = cx.getWrapFactory().wrap(cx, this, rval, type);
        if (rval instanceof Scriptable) {
            rval = ((Scriptable) rval).getDefaultValue(hint);
        }
        return rval;
    }

    Field field;
    Object javaObject;
}
