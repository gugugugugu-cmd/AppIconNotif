package com.example.appiconnotif

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * 轻量反射工具。
 *
 * libxposed API 102 不再提供旧版 XposedBridge 的 XposedHelpers，
 * 这里按本项目所需实现最小集合：按名称查找类 / 方法、读写任意字段、
 * 容错调用任意方法（均沿父类链向上查找）。
 */
object XposedCompat {

    fun findClass(className: String, classLoader: ClassLoader): Class<*> {
        return try {
            Class.forName(className, false, classLoader)
        } catch (e: ClassNotFoundException) {
            throw ClassNotFoundException(className, e)
        }
    }

    /**
     * 在 clazz 及其父类链上查找与 name 匹配的方法。
     * parameterTypes 中的 null 表示“任意类型”，仅要求参数个数一致；
     * 给出具体类型时允许是其声明参数类型的子类。
     * 若同一层级有多个候选，优先选择类型完全匹配的方法。
     */
    fun findMethodBestMatch(clazz: Class<*>, name: String, vararg parameterTypes: Class<*>?): Method {
        var best: Method? = null
        var bestScore = -1
        var c: Class<*>? = clazz
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != name || m.parameterTypes.size != parameterTypes.size) continue
                var score = 0
                var ok = true
                for (i in parameterTypes.indices) {
                    val want = parameterTypes[i] ?: continue
                    val have = m.parameterTypes[i]
                    when {
                        // 完全一致最优；同继承链（如声明 ImageView、提示 View）也可匹配，
                        // 以便兼容不同 ROM 的方法签名差异
                        have == want -> score += 2
                        have.isAssignableFrom(want) -> score += 1
                        want.isAssignableFrom(have) -> score += 1
                        else -> {
                            ok = false
                            break
                        }
                    }
                }
                if (ok && score > bestScore) {
                    best = m
                    bestScore = score
                }
            }
            if (best != null) break
            c = c.superclass
        }
        best?.let {
            it.isAccessible = true
            return it
        }
        throw NoSuchMethodException("${clazz.name}#$name(${parameterTypes.joinToString()})")
    }

    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        throw NoSuchFieldException("${obj.javaClass.name}.$fieldName")
    }

    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        throw NoSuchFieldException("${obj.javaClass.name}.$fieldName")
    }

    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            for (m in c.declaredMethods) {
                if (m.name != methodName || m.parameterTypes.size != args.size) continue
                if (!argsMatch(m.parameterTypes, args)) continue
                m.isAccessible = true
                return try {
                    m.invoke(obj, *args)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            c = c.superclass
        }
        throw NoSuchMethodException("${obj.javaClass.name}#$methodName(${args.size} args)")
    }

    private fun argsMatch(declared: Array<Class<*>>, args: Array<out Any?>): Boolean {
        for (i in declared.indices) {
            val arg = args[i]
            val d = declared[i]
            if (arg == null) {
                if (d.isPrimitive) return false
                continue
            }
            if (d.isPrimitive) {
                if (!wrapperOf(d).isInstance(arg)) return false
                continue
            }
            if (!d.isAssignableFrom(arg.javaClass)) return false
        }
        return true
    }

    private fun wrapperOf(primitive: Class<*>): Class<*> = when (primitive) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> primitive
    }
}
