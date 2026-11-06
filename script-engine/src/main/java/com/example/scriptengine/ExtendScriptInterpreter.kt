package com.example.scriptengine

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

sealed interface EsValue {
    object Undefined : EsValue {
        override fun toString(): String = "undefined"
    }

    object Null : EsValue {
        override fun toString(): String = "null"
    }

    data class Bool(val value: Boolean) : EsValue {
        override fun toString(): String = value.toString()
    }

    data class Num(val value: Double) : EsValue {
        override fun toString(): String =
            if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                value.toString()
            }
    }

    data class Str(val value: String) : EsValue {
        override fun toString(): String = value
    }

    open class Obj(
        val className: String = "Object",
        val props: MutableMap<String, EsValue> = LinkedHashMap(),
        var getterHook: ((String) -> EsValue?)? = null,
        var setterHook: ((String, EsValue) -> Boolean)? = null
    ) : EsValue {
        open fun getProp(name: String): EsValue {
            getterHook?.invoke(name)?.let { return it }
            return props[name] ?: Undefined
        }

        open fun setProp(name: String, value: EsValue) {
            if (setterHook?.invoke(name, value) == true) return
            props[name] = value
        }

        override fun toString(): String {
            val custom = props["__toStringText__"]
            if (custom is Str) return custom.value
            return "[object $className]"
        }
    }

    class Arr(val elements: MutableList<EsValue> = ArrayList()) : Obj(className = "Array") {
        override fun getProp(name: String): EsValue {
            if (name == "length") return Num(elements.size.toDouble())
            val idx = name.toIntOrNull()
            if (idx != null) {
                return if (idx in elements.indices) elements[idx] else Undefined
            }
            return super.getProp(name)
        }

        override fun setProp(name: String, value: EsValue) {
            if (name == "length") {
                val n = value.toNum().toInt().coerceAtLeast(0)
                while (elements.size > n) elements.removeAt(elements.lastIndex)
                while (elements.size < n) elements.add(Undefined)
                return
            }
            val idx = name.toIntOrNull()
            if (idx != null && idx >= 0) {
                while (elements.size <= idx) elements.add(Undefined)
                elements[idx] = value
                return
            }
            super.setProp(name, value)
        }

        override fun toString(): String = elements.joinToString(",") { it.toJsString() }
    }

    interface Callable : EsValue {
        val fnName: String
        val prototypeClassName: String get() = fnName.ifBlank { "Function" }
        fun call(thisArg: EsValue, args: List<EsValue>, deadlineNs: Long): EsValue
        fun construct(args: List<EsValue>, deadlineNs: Long): EsValue =
            call(Obj(prototypeClassName), args, deadlineNs)
    }

    class NativeFn(
        override val fnName: String,
        override val prototypeClassName: String = fnName,
        private val constructBlock: ((args: List<EsValue>, deadlineNs: Long) -> EsValue)? = null,
        private val invokeBlock: (thisArg: EsValue, args: List<EsValue>, deadlineNs: Long) -> EsValue
    ) : Obj(className = "Function"), Callable {
        override fun call(thisArg: EsValue, args: List<EsValue>, deadlineNs: Long): EsValue =
            invokeBlock(thisArg, args, deadlineNs)

        override fun construct(args: List<EsValue>, deadlineNs: Long): EsValue =
            constructBlock?.invoke(args, deadlineNs) ?: invokeBlock(Obj(prototypeClassName), args, deadlineNs)
    }
}

fun EsValue.isTruthy(): Boolean = when (this) {
    EsValue.Undefined, EsValue.Null -> false
    is EsValue.Bool -> value
    is EsValue.Num -> value != 0.0 && !value.isNaN()
    is EsValue.Str -> value.isNotEmpty()
    else -> true
}

fun EsValue.toNum(): Double = when (this) {
    EsValue.Undefined -> Double.NaN
    EsValue.Null -> 0.0
    is EsValue.Bool -> if (value) 1.0 else 0.0
    is EsValue.Num -> value
    is EsValue.Str -> value.trim().let { if (it.isEmpty()) 0.0 else it.toDoubleOrNull() ?: Double.NaN }
    is EsValue.Arr -> if (elements.isEmpty()) 0.0 else if (elements.size == 1) elements[0].toNum() else Double.NaN
    else -> Double.NaN
}

fun EsValue.toJsString(): String = when (this) {
    EsValue.Undefined -> "undefined"
    EsValue.Null -> "null"
    is EsValue.Bool -> value.toString()
    is EsValue.Num -> toString()
    is EsValue.Str -> value
    else -> toString()
}

class EsScope(val parent: EsScope? = null, val vars: MutableMap<String, EsValue> = LinkedHashMap()) {
    fun declareVar(name: String, initial: EsValue = EsValue.Undefined) {
        if (!vars.containsKey(name) || initial !== EsValue.Undefined) {
            vars[name] = initial
        }
    }

    fun get(name: String): EsValue {
        var s: EsScope? = this
        while (s != null) {
            if (s.vars.containsKey(name)) return s.vars[name] ?: EsValue.Undefined
            s = s.parent
        }
        return EsValue.Undefined
    }

    fun set(name: String, value: EsValue) {
        var s: EsScope? = this
        while (s != null) {
            if (s.vars.containsKey(name)) {
                s.vars[name] = value
                return
            }
            if (s.parent == null) {
                s.vars[name] = value
                return
            }
            s = s.parent
        }
    }
}

class EsRuntimeException(val thrownValue: EsValue) : RuntimeException(thrownValue.toJsString())
class EsTimeoutException : RuntimeException("ExtendScript execution timed out (> 5s)")

/**
 * Lightweight, deterministic ES3/ES5 + ExtendScript interpreter designed to run on a background
 * thread both on Android and inside local JVM Robolectric unit tests without WebView main-thread dependencies.
 */
class ExtendScriptInterpreter(
    val globalScope: EsScope = EsScope(),
    private val includeResolver: ((String) -> String?)? = null
) {
    val globalObject = EsValue.Obj(
        className = "Global",
        getterHook = { k -> if (globalScope.vars.containsKey(k)) globalScope.vars[k] else null },
        setterHook = { k, v ->
            globalScope.vars[k] = v
            true
        }
    )

    init {
        installStandardLibrary()
    }

    fun evaluate(source: String, timeoutMs: Long = 5_000L, thisObjOverride: EsValue? = null): EsValue {
        val preprocessed = preprocessDirectives(source, mutableSetOf())
        val tokens = tokenize(preprocessed)
        val parser = Parser(tokens)
        val program = parser.parseProgram()
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(50L) * 1_000_000L
        val rootThis = thisObjOverride ?: globalObject
        globalScope.declareVar("thisObj", rootThis)
        return executeBlockInScope(program, globalScope, rootThis, deadlineNs)
    }

    private fun preprocessDirectives(raw: String, visited: MutableSet<String>): String {
        val sb = StringBuilder(raw.length)
        for (line in raw.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#include")) {
                val m = Regex("""#include\s+["']([^"']+)["']""").find(trimmed)
                val path = m?.groupValues?.getOrNull(1)
                if (path != null && visited.add(path)) {
                    val inc = includeResolver?.invoke(path)
                    if (inc != null) {
                        sb.appendLine(preprocessDirectives(inc, visited))
                    }
                }
            } else if (trimmed.startsWith("#target") || trimmed.startsWith("#includepath") || trimmed.startsWith("#strict")) {
                sb.appendLine("// $trimmed")
            } else {
                sb.appendLine(line)
            }
        }
        return sb.toString()
    }

    private fun installStandardLibrary() {
        globalScope.declareVar("undefined", EsValue.Undefined)
        globalScope.declareVar("NaN", EsValue.Num(Double.NaN))
        globalScope.declareVar("Infinity", EsValue.Num(Double.POSITIVE_INFINITY))
        globalScope.declareVar("$.global", globalObject)

        val dollarObj = EsValue.Obj("Dollar").apply {
            setProp("os", EsValue.Str("Android"))
            setProp("version", EsValue.Str("4.5.6"))
            setProp("writeln", EsValue.NativeFn("writeln") { _, _, _ -> EsValue.Undefined })
            setProp("write", EsValue.NativeFn("write") { _, _, _ -> EsValue.Undefined })
            setProp("sleep", EsValue.NativeFn("sleep") { _, _, _ -> EsValue.Undefined })
            setProp("gc", EsValue.NativeFn("gc") { _, _, _ -> EsValue.Undefined })
        }
        globalScope.declareVar("$", dollarObj)

        globalScope.declareVar("isNaN", EsValue.NativeFn("isNaN") { _, args, _ ->
            EsValue.Bool(args.firstOrNull()?.toNum()?.isNaN() ?: true)
        })
        globalScope.declareVar("isFinite", EsValue.NativeFn("isFinite") { _, args, _ ->
            EsValue.Bool(args.firstOrNull()?.toNum()?.isFinite() ?: false)
        })
        globalScope.declareVar("parseFloat", EsValue.NativeFn("parseFloat") { _, args, _ ->
            val s = args.firstOrNull()?.toJsString()?.trim().orEmpty()
            val m = Regex("""^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?""").find(s)
            EsValue.Num(m?.value?.toDoubleOrNull() ?: Double.NaN)
        })
        globalScope.declareVar("parseInt", EsValue.NativeFn("parseInt") { _, args, _ ->
            val s = args.firstOrNull()?.toJsString()?.trim().orEmpty()
            val radix = args.getOrNull(1)?.toNum()?.toInt()?.takeIf { it in 2..36 } ?: 10
            val clean = if (radix == 16) s.removePrefix("0x").removePrefix("0X") else s
            val m = Regex("""^[+-]?[0-9a-zA-Z]+""").find(clean)
            EsValue.Num(m?.value?.toLongOrNull(radix)?.toDouble() ?: Double.NaN)
        })
        globalScope.declareVar("Number", EsValue.NativeFn("Number",
            invokeBlock = { _, args, _ -> EsValue.Num(args.firstOrNull()?.toNum() ?: 0.0) },
            constructBlock = { args, _ -> EsValue.Num(args.firstOrNull()?.toNum() ?: 0.0) }
        ))
        globalScope.declareVar("String", EsValue.NativeFn("String",
            invokeBlock = { _, args, _ -> EsValue.Str(args.firstOrNull()?.toJsString().orEmpty()) },
            constructBlock = { args, _ -> EsValue.Str(args.firstOrNull()?.toJsString().orEmpty()) }
        ))
        globalScope.declareVar("Boolean", EsValue.NativeFn("Boolean",
            invokeBlock = { _, args, _ -> EsValue.Bool(args.firstOrNull()?.isTruthy() ?: false) },
            constructBlock = { args, _ -> EsValue.Bool(args.firstOrNull()?.isTruthy() ?: false) }
        ))
        globalScope.declareVar("Array", EsValue.NativeFn("Array", "Array",
            invokeBlock = { _, args, _ ->
                if (args.size == 1 && args[0] is EsValue.Num) {
                    val len = args[0].toNum().toInt().coerceIn(0, 100_000)
                    EsValue.Arr(MutableList(len) { EsValue.Undefined })
                } else {
                    EsValue.Arr(args.toMutableList())
                }
            },
            constructBlock = { args, _ ->
                if (args.size == 1 && args[0] is EsValue.Num) {
                    val len = args[0].toNum().toInt().coerceIn(0, 100_000)
                    EsValue.Arr(MutableList(len) { EsValue.Undefined })
                } else {
                    EsValue.Arr(args.toMutableList())
                }
            }
        ).apply {
            setProp("isArray", EsValue.NativeFn("isArray") { _, args, _ ->
                EsValue.Bool(args.firstOrNull() is EsValue.Arr)
            })
        })
        globalScope.declareVar("Object", EsValue.NativeFn("Object", "Object",
            invokeBlock = { _, args, _ -> (args.firstOrNull() as? EsValue.Obj) ?: EsValue.Obj() },
            constructBlock = { _, _ -> EsValue.Obj() }
        ).apply {
            setProp("keys", EsValue.NativeFn("keys") { _, args, _ ->
                val o = args.firstOrNull() as? EsValue.Obj ?: return@NativeFn EsValue.Arr()
                EsValue.Arr(o.props.keys.map { EsValue.Str(it) }.toMutableList())
            })
        })
        globalScope.declareVar("Error", EsValue.NativeFn("Error", "Error",
            invokeBlock = { _, args, _ ->
                val msg = args.firstOrNull()?.toJsString().orEmpty()
                EsValue.Obj("Error").apply {
                    setProp("message", EsValue.Str(msg))
                    setProp("__toStringText__", EsValue.Str("Error: $msg"))
                }
            },
            constructBlock = { args, _ ->
                val msg = args.firstOrNull()?.toJsString().orEmpty()
                EsValue.Obj("Error").apply {
                    setProp("message", EsValue.Str(msg))
                    setProp("__toStringText__", EsValue.Str("Error: $msg"))
                }
            }
        ))

        val mathObj = EsValue.Obj("Math").apply {
            setProp("PI", EsValue.Num(Math.PI))
            setProp("E", EsValue.Num(Math.E))
            setProp("abs", EsValue.NativeFn("abs") { _, a, _ -> EsValue.Num(abs(a.firstOrNull().orZero())) })
            setProp("sqrt", EsValue.NativeFn("sqrt") { _, a, _ -> EsValue.Num(sqrt(a.firstOrNull().orZero())) })
            setProp("sin", EsValue.NativeFn("sin") { _, a, _ -> EsValue.Num(sin(a.firstOrNull().orZero())) })
            setProp("cos", EsValue.NativeFn("cos") { _, a, _ -> EsValue.Num(cos(a.firstOrNull().orZero())) })
            setProp("tan", EsValue.NativeFn("tan") { _, a, _ -> EsValue.Num(tan(a.firstOrNull().orZero())) })
            setProp("asin", EsValue.NativeFn("asin") { _, a, _ -> EsValue.Num(asin(a.firstOrNull().orZero())) })
            setProp("acos", EsValue.NativeFn("acos") { _, a, _ -> EsValue.Num(acos(a.firstOrNull().orZero())) })
            setProp("atan", EsValue.NativeFn("atan") { _, a, _ -> EsValue.Num(atan(a.firstOrNull().orZero())) })
            setProp("atan2", EsValue.NativeFn("atan2") { _, a, _ ->
                EsValue.Num(atan2(a.getOrNull(0).orZero(), a.getOrNull(1).orZero()))
            })
            setProp("floor", EsValue.NativeFn("floor") { _, a, _ -> EsValue.Num(floor(a.firstOrNull().orZero())) })
            setProp("ceil", EsValue.NativeFn("ceil") { _, a, _ -> EsValue.Num(ceil(a.firstOrNull().orZero())) })
            setProp("round", EsValue.NativeFn("round") { _, a, _ -> EsValue.Num(round(a.firstOrNull().orZero())) })
            setProp("exp", EsValue.NativeFn("exp") { _, a, _ -> EsValue.Num(exp(a.firstOrNull().orZero())) })
            setProp("log", EsValue.NativeFn("log") { _, a, _ -> EsValue.Num(ln(a.firstOrNull().orZero())) })
            setProp("pow", EsValue.NativeFn("pow") { _, a, _ ->
                EsValue.Num(a.getOrNull(0).orZero().pow(a.getOrNull(1).orZero()))
            })
            setProp("max", EsValue.NativeFn("max") { _, a, _ ->
                if (a.isEmpty()) EsValue.Num(Double.NEGATIVE_INFINITY)
                else EsValue.Num(a.maxOf { it.toNum() })
            })
            setProp("min", EsValue.NativeFn("min") { _, a, _ ->
                if (a.isEmpty()) EsValue.Num(Double.POSITIVE_INFINITY)
                else EsValue.Num(a.minOf { it.toNum() })
            })
            setProp("random", EsValue.NativeFn("random") { _, _, _ -> EsValue.Num(Random.nextDouble()) })
        }
        globalScope.declareVar("Math", mathObj)

        val jsonObj = EsValue.Obj("JSON").apply {
            setProp("stringify", EsValue.NativeFn("stringify") { _, args, _ ->
                EsValue.Str(stringifyEsValue(args.firstOrNull() ?: EsValue.Undefined))
            })
            setProp("parse", EsValue.NativeFn("parse") { _, args, _ ->
                parseJsonToEsValue(args.firstOrNull()?.toJsString().orEmpty())
            })
        }
        globalScope.declareVar("JSON", jsonObj)

        globalScope.declareVar("alert", EsValue.NativeFn("alert") { _, _, _ -> EsValue.Undefined })
    }

    private fun EsValue?.orZero(): Double = this?.toNum() ?: 0.0

    private fun stringifyEsValue(v: EsValue): String = when (v) {
        EsValue.Undefined, EsValue.Null -> "null"
        is EsValue.Bool -> v.value.toString()
        is EsValue.Num -> if (v.value.isFinite()) v.toString() else "null"
        is EsValue.Str -> org.json.JSONObject.quote(v.value)
        is EsValue.Arr -> v.elements.joinToString(",", "[", "]") { stringifyEsValue(it) }
        is EsValue.Obj -> v.props.entries
            .filter { it.value !is EsValue.Callable && !it.key.startsWith("__") }
            .joinToString(",", "{", "}") { "${org.json.JSONObject.quote(it.key)}:${stringifyEsValue(it.value)}" }
        else -> "null"
    }

    private fun parseJsonToEsValue(raw: String): EsValue {
        val t = raw.trim()
        if (t.isEmpty() || t == "null") return EsValue.Null
        if (t == "true") return EsValue.Bool(true)
        if (t == "false") return EsValue.Bool(false)
        t.toDoubleOrNull()?.let { return EsValue.Num(it) }
        if (t.startsWith("{")) {
            val jo = org.json.JSONObject(t)
            return jsonObjectToEs(jo)
        }
        if (t.startsWith("[")) {
            val ja = org.json.JSONArray(t)
            return jsonArrayToEs(ja)
        }
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2) {
            return EsValue.Str(t.substring(1, t.length - 1))
        }
        return EsValue.Undefined
    }

    private fun jsonObjectToEs(jo: org.json.JSONObject): EsValue.Obj {
        val obj = EsValue.Obj()
        val keys = jo.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            obj.setProp(k, jsonAnyToEs(jo.opt(k)))
        }
        return obj
    }

    private fun jsonArrayToEs(ja: org.json.JSONArray): EsValue.Arr {
        val list = ArrayList<EsValue>(ja.length())
        for (i in 0 until ja.length()) {
            list.add(jsonAnyToEs(ja.opt(i)))
        }
        return EsValue.Arr(list)
    }

    private fun jsonAnyToEs(v: Any?): EsValue = when (v) {
        null, org.json.JSONObject.NULL -> EsValue.Null
        is Boolean -> EsValue.Bool(v)
        is Number -> EsValue.Num(v.toDouble())
        is String -> EsValue.Str(v)
        is org.json.JSONObject -> jsonObjectToEs(v)
        is org.json.JSONArray -> jsonArrayToEs(v)
        else -> EsValue.Str(v.toString())
    }

    // =========================================================================
    // Tokenizer
    // =========================================================================

    private enum class TokKind {
        IDENT, NUM, STR, PUNCT, OP, EOF
    }

    private data class Tok(val kind: TokKind, val text: String, val line: Int)

    private fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        var line = 1
        val n = src.length
        while (i < n) {
            val c = src[i]
            if (c == '\n') {
                line++
                i++
                continue
            }
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '/') {
                i += 2
                while (i < n && src[i] != '\n') i++
                continue
            }
            if (c == '/' && i + 1 < n && src[i + 1] == '*') {
                i += 2
                while (i + 1 < n && !(src[i] == '*' && src[i + 1] == '/')) {
                    if (src[i] == '\n') line++
                    i++
                }
                i = (i + 2).coerceAtMost(n)
                continue
            }
            if (c == '\'' || c == '"') {
                val quote = c
                i++
                val sb = StringBuilder()
                while (i < n && src[i] != quote) {
                    if (src[i] == '\\' && i + 1 < n) {
                        i++
                        when (val esc = src[i]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '\\' -> sb.append('\\')
                            '\'' -> sb.append('\'')
                            '"' -> sb.append('"')
                            'u' -> {
                                if (i + 4 < n) {
                                    val hex = src.substring(i + 1, i + 5)
                                    sb.append((hex.toIntOrNull(16) ?: 63).toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        if (src[i] == '\n') line++
                        sb.append(src[i])
                    }
                    i++
                }
                if (i < n && src[i] == quote) i++
                out.add(Tok(TokKind.STR, sb.toString(), line))
                continue
            }
            if (c.isDigit() || (c == '.' && i + 1 < n && src[i + 1].isDigit())) {
                val start = i
                if (c == '0' && i + 1 < n && (src[i + 1] == 'x' || src[i + 1] == 'X')) {
                    i += 2
                    while (i < n && (src[i].isDigit() || src[i] in 'a'..'f' || src[i] in 'A'..'F')) i++
                } else {
                    while (i < n && src[i].isDigit()) i++
                    if (i < n && src[i] == '.') {
                        i++
                        while (i < n && src[i].isDigit()) i++
                    }
                    if (i < n && (src[i] == 'e' || src[i] == 'E')) {
                        i++
                        if (i < n && (src[i] == '+' || src[i] == '-')) i++
                        while (i < n && src[i].isDigit()) i++
                    }
                }
                out.add(Tok(TokKind.NUM, src.substring(start, i), line))
                continue
            }
            if (c.isLetter() || c == '_' || c == '$') {
                val start = i
                i++
                while (i < n && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
                out.add(Tok(TokKind.IDENT, src.substring(start, i), line))
                continue
            }
            val three = if (i + 3 <= n) src.substring(i, i + 3) else ""
            if (three in setOf("===", "!==", ">>>")) {
                out.add(Tok(TokKind.OP, three, line))
                i += 3
                continue
            }
            val two = if (i + 2 <= n) src.substring(i, i + 2) else ""
            if (two in setOf("==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=", "<<", ">>")) {
                out.add(Tok(TokKind.OP, two, line))
                i += 2
                continue
            }
            if (c in "(){}[];,.:?") {
                out.add(Tok(TokKind.PUNCT, c.toString(), line))
                i++
                continue
            }
            out.add(Tok(TokKind.OP, c.toString(), line))
            i++
        }
        out.add(Tok(TokKind.EOF, "", line))
        return out
    }

    // =========================================================================
    // AST Nodes & Parser
    // =========================================================================

    private sealed interface Stmt {
        data class Block(val stmts: List<Stmt>) : Stmt
        data class VarDecl(val decls: List<Pair<String, Expr?>>) : Stmt
        data class FnDecl(val name: String, val params: List<String>, val body: List<Stmt>) : Stmt
        data class If(val cond: Expr, val thenBranch: Stmt, val elseBranch: Stmt?) : Stmt
        data class While(val cond: Expr, val body: Stmt) : Stmt
        data class DoWhile(val body: Stmt, val cond: Expr) : Stmt
        data class For(val init: Stmt?, val cond: Expr?, val step: Expr?, val body: Stmt) : Stmt
        data class ForIn(val varName: String, val target: Expr, val body: Stmt) : Stmt
        data class Switch(val disc: Expr, val cases: List<Pair<Expr?, List<Stmt>>>) : Stmt
        data class Return(val expr: Expr?) : Stmt
        object Break : Stmt
        object Continue : Stmt
        data class Throw(val expr: Expr) : Stmt
        data class TryCatch(
            val tryBlock: List<Stmt>,
            val catchVar: String?,
            val catchBlock: List<Stmt>?,
            val finallyBlock: List<Stmt>?
        ) : Stmt
        data class ExprStmt(val expr: Expr) : Stmt
        object Empty : Stmt
    }

    private sealed interface Expr {
        data class Literal(val value: EsValue) : Expr
        object ThisExpr : Expr
        data class Ident(val name: String) : Expr
        data class ArrayLit(val items: List<Expr>) : Expr
        data class ObjectLit(val entries: List<Pair<String, Expr>>) : Expr
        data class FnExpr(val name: String?, val params: List<String>, val body: List<Stmt>) : Expr
        data class Member(val obj: Expr, val prop: Expr, val computed: Boolean) : Expr
        data class Call(val callee: Expr, val args: List<Expr>, val isNew: Boolean) : Expr
        data class Unary(val op: String, val operand: Expr, val prefix: Boolean = true) : Expr
        data class Binary(val op: String, val left: Expr, val right: Expr) : Expr
        data class Assign(val op: String, val target: Expr, val value: Expr) : Expr
        data class Conditional(val cond: Expr, val ifTrue: Expr, val ifFalse: Expr) : Expr
        data class Sequence(val exprs: List<Expr>) : Expr
    }

    private inner class Parser(private val tokens: List<Tok>) {
        private var pos = 0

        private fun peek(offset: Int = 0): Tok =
            tokens.getOrElse(pos + offset) { tokens.last() }

        private fun next(): Tok =
            tokens.getOrElse(pos++) { tokens.last() }

        private fun matchText(t: String): Boolean {
            if (peek().text == t && peek().kind != TokKind.STR) {
                pos++
                return true
            }
            return false
        }

        private fun expectText(t: String) {
            val tok = next()
            if (tok.text != t) {
                throw IllegalArgumentException("Expected '$t' at line ${tok.line}, got '${tok.text}'")
            }
        }

        private fun consumeOptionalSemicolon() {
            matchText(";")
        }

        fun parseProgram(): List<Stmt> {
            val list = ArrayList<Stmt>()
            while (peek().kind != TokKind.EOF) {
                list.add(parseStmt())
            }
            return list
        }

        private fun parseStmt(): Stmt {
            val t = peek()
            if (t.kind == TokKind.PUNCT && t.text == ";") {
                pos++
                return Stmt.Empty
            }
            if (t.kind == TokKind.PUNCT && t.text == "{") {
                return Stmt.Block(parseBlockBody())
            }
            if (t.kind == TokKind.IDENT) {
                when (t.text) {
                    "var", "let", "const" -> {
                        pos++
                        val decls = parseVarDeclList()
                        consumeOptionalSemicolon()
                        return Stmt.VarDecl(decls)
                    }
                    "function" -> {
                        pos++
                        val fnName = next().text
                        val params = parseParamList()
                        val body = parseBlockBody()
                        return Stmt.FnDecl(fnName, params, body)
                    }
                    "if" -> {
                        pos++
                        expectText("(")
                        val cond = parseExpr()
                        expectText(")")
                        val thenB = parseStmt()
                        val elseB = if (peek().kind == TokKind.IDENT && peek().text == "else") {
                            pos++
                            parseStmt()
                        } else null
                        return Stmt.If(cond, thenB, elseB)
                    }
                    "while" -> {
                        pos++
                        expectText("(")
                        val cond = parseExpr()
                        expectText(")")
                        return Stmt.While(cond, parseStmt())
                    }
                    "do" -> {
                        pos++
                        val body = parseStmt()
                        expectText("while")
                        expectText("(")
                        val cond = parseExpr()
                        expectText(")")
                        consumeOptionalSemicolon()
                        return Stmt.DoWhile(body, cond)
                    }
                    "for" -> {
                        pos++
                        expectText("(")
                        return parseForRest()
                    }
                    "switch" -> {
                        pos++
                        expectText("(")
                        val disc = parseExpr()
                        expectText(")")
                        expectText("{")
                        val cases = ArrayList<Pair<Expr?, List<Stmt>>>()
                        while (!matchText("}") && peek().kind != TokKind.EOF) {
                            val caseExpr = if (matchText("case")) {
                                val e = parseExpr()
                                expectText(":")
                                e
                            } else {
                                expectText("default")
                                expectText(":")
                                null
                            }
                            val caseStmts = ArrayList<Stmt>()
                            while (peek().text != "}" &&
                                !(peek().kind == TokKind.IDENT && (peek().text == "case" || peek().text == "default")) &&
                                peek().kind != TokKind.EOF
                            ) {
                                caseStmts.add(parseStmt())
                            }
                            cases.add(caseExpr to caseStmts)
                        }
                        return Stmt.Switch(disc, cases)
                    }
                    "return" -> {
                        val retLine = next().line
                        if (peek().text == ";" || peek().text == "}" || peek().kind == TokKind.EOF || peek().line > retLine) {
                            consumeOptionalSemicolon()
                            return Stmt.Return(null)
                        }
                        val e = parseExpr()
                        consumeOptionalSemicolon()
                        return Stmt.Return(e)
                    }
                    "break" -> {
                        pos++
                        consumeOptionalSemicolon()
                        return Stmt.Break
                    }
                    "continue" -> {
                        pos++
                        consumeOptionalSemicolon()
                        return Stmt.Continue
                    }
                    "throw" -> {
                        pos++
                        val e = parseExpr()
                        consumeOptionalSemicolon()
                        return Stmt.Throw(e)
                    }
                    "try" -> {
                        pos++
                        val tryBlock = parseBlockBody()
                        var catchVar: String? = null
                        var catchBlock: List<Stmt>? = null
                        var finallyBlock: List<Stmt>? = null
                        if (peek().kind == TokKind.IDENT && peek().text == "catch") {
                            pos++
                            if (matchText("(")) {
                                catchVar = next().text
                                expectText(")")
                            }
                            catchBlock = parseBlockBody()
                        }
                        if (peek().kind == TokKind.IDENT && peek().text == "finally") {
                            pos++
                            finallyBlock = parseBlockBody()
                        }
                        return Stmt.TryCatch(tryBlock, catchVar, catchBlock, finallyBlock)
                    }
                }
            }
            val expr = parseExpr()
            consumeOptionalSemicolon()
            return Stmt.ExprStmt(expr)
        }

        private fun parseForRest(): Stmt {
            if (matchText(";")) {
                val cond = if (peek().text == ";") null else parseExpr()
                expectText(";")
                val step = if (peek().text == ")") null else parseExpr()
                expectText(")")
                return Stmt.For(null, cond, step, parseStmt())
            }
            if (peek().kind == TokKind.IDENT && peek().text in setOf("var", "let", "const")) {
                pos++
                val firstName = next().text
                if (peek().kind == TokKind.IDENT && peek().text == "in") {
                    pos++
                    val target = parseExpr()
                    expectText(")")
                    return Stmt.ForIn(firstName, target, parseStmt())
                }
                val decls = ArrayList<Pair<String, Expr?>>()
                val firstInit = if (matchText("=")) parseAssign() else null
                decls.add(firstName to firstInit)
                while (matchText(",")) {
                    val nm = next().text
                    val init = if (matchText("=")) parseAssign() else null
                    decls.add(nm to init)
                }
                expectText(";")
                val cond = if (peek().text == ";") null else parseExpr()
                expectText(";")
                val step = if (peek().text == ")") null else parseExpr()
                expectText(")")
                return Stmt.For(Stmt.VarDecl(decls), cond, step, parseStmt())
            }
            if (peek().kind == TokKind.IDENT && peek(1).kind == TokKind.IDENT && peek(1).text == "in") {
                val nm = next().text
                next() // 'in'
                val target = parseExpr()
                expectText(")")
                return Stmt.ForIn(nm, target, parseStmt())
            }
            val initExpr = parseExpr()
            expectText(";")
            val cond = if (peek().text == ";") null else parseExpr()
            expectText(";")
            val step = if (peek().text == ")") null else parseExpr()
            expectText(")")
            return Stmt.For(Stmt.ExprStmt(initExpr), cond, step, parseStmt())
        }

        private fun parseVarDeclList(): List<Pair<String, Expr?>> {
            val list = ArrayList<Pair<String, Expr?>>()
            do {
                val nm = next().text
                val init = if (matchText("=")) parseAssign() else null
                list.add(nm to init)
            } while (matchText(","))
            return list
        }

        private fun parseParamList(): List<String> {
            expectText("(")
            val params = ArrayList<String>()
            if (!matchText(")")) {
                do {
                    params.add(next().text)
                } while (matchText(","))
                expectText(")")
            }
            return params
        }

        private fun parseBlockBody(): List<Stmt> {
            expectText("{")
            val list = ArrayList<Stmt>()
            while (!matchText("}") && peek().kind != TokKind.EOF) {
                list.add(parseStmt())
            }
            return list
        }

        private fun parseExpr(): Expr {
            val first = parseAssign()
            if (peek().text != ",") return first
            val list = mutableListOf(first)
            while (matchText(",")) {
                list.add(parseAssign())
            }
            return Expr.Sequence(list)
        }

        private fun parseAssign(): Expr {
            val left = parseConditional()
            val op = peek().text
            if (peek().kind == TokKind.OP && op in setOf("=", "+=", "-=", "*=", "/=", "%=")) {
                pos++
                val right = parseAssign()
                return Expr.Assign(op, left, right)
            }
            return left
        }

        private fun parseConditional(): Expr {
            val cond = parseLogicalOr()
            if (matchText("?")) {
                val t = parseAssign()
                expectText(":")
                val f = parseAssign()
                return Expr.Conditional(cond, t, f)
            }
            return cond
        }

        private fun parseLogicalOr(): Expr {
            var left = parseLogicalAnd()
            while (peek().kind == TokKind.OP && peek().text == "||") {
                val op = next().text
                left = Expr.Binary(op, left, parseLogicalAnd())
            }
            return left
        }

        private fun parseLogicalAnd(): Expr {
            var left = parseBitwiseOr()
            while (peek().kind == TokKind.OP && peek().text == "&&") {
                val op = next().text
                left = Expr.Binary(op, left, parseBitwiseOr())
            }
            return left
        }

        private fun parseBitwiseOr(): Expr {
            var left = parseBitwiseXor()
            while (peek().kind == TokKind.OP && peek().text == "|") {
                val op = next().text
                left = Expr.Binary(op, left, parseBitwiseXor())
            }
            return left
        }

        private fun parseBitwiseXor(): Expr {
            var left = parseBitwiseAnd()
            while (peek().kind == TokKind.OP && peek().text == "^") {
                val op = next().text
                left = Expr.Binary(op, left, parseBitwiseAnd())
            }
            return left
        }

        private fun parseBitwiseAnd(): Expr {
            var left = parseEquality()
            while (peek().kind == TokKind.OP && peek().text == "&") {
                val op = next().text
                left = Expr.Binary(op, left, parseEquality())
            }
            return left
        }

        private fun parseEquality(): Expr {
            var left = parseRelational()
            while (peek().kind == TokKind.OP && peek().text in setOf("==", "!=", "===", "!==")) {
                val op = next().text
                left = Expr.Binary(op, left, parseRelational())
            }
            return left
        }

        private fun parseRelational(): Expr {
            var left = parseShift()
            while (
                (peek().kind == TokKind.OP && peek().text in setOf("<", "<=", ">", ">=")) ||
                (peek().kind == TokKind.IDENT && peek().text in setOf("instanceof", "in"))
            ) {
                val op = next().text
                left = Expr.Binary(op, left, parseShift())
            }
            return left
        }

        private fun parseShift(): Expr {
            var left = parseAdditive()
            while (peek().kind == TokKind.OP && peek().text in setOf("<<", ">>", ">>>")) {
                val op = next().text
                left = Expr.Binary(op, left, parseAdditive())
            }
            return left
        }

        private fun parseAdditive(): Expr {
            var left = parseMultiplicative()
            while (peek().kind == TokKind.OP && peek().text in setOf("+", "-")) {
                val op = next().text
                left = Expr.Binary(op, left, parseMultiplicative())
            }
            return left
        }

        private fun parseMultiplicative(): Expr {
            var left = parseUnary()
            while (peek().kind == TokKind.OP && peek().text in setOf("*", "/", "%")) {
                val op = next().text
                left = Expr.Binary(op, left, parseUnary())
            }
            return left
        }

        private fun parseUnary(): Expr {
            val t = peek()
            if (t.kind == TokKind.OP && t.text in setOf("!", "-", "+", "~", "++", "--")) {
                pos++
                return Expr.Unary(t.text, parseUnary(), prefix = true)
            }
            if (t.kind == TokKind.IDENT && t.text in setOf("typeof", "void", "delete")) {
                pos++
                return Expr.Unary(t.text, parseUnary(), prefix = true)
            }
            return parsePostfix()
        }

        private fun parsePostfix(): Expr {
            val expr = parseCallMember()
            if (peek().kind == TokKind.OP && peek().text in setOf("++", "--")) {
                val op = next().text
                return Expr.Unary(op, expr, prefix = false)
            }
            return expr
        }

        private fun parseCallMember(): Expr {
            var expr = if (peek().kind == TokKind.IDENT && peek().text == "new") {
                pos++
                val callee = parsePrimaryMemberOnly()
                val args = if (peek().text == "(") parseArgList() else emptyList()
                Expr.Call(callee, args, isNew = true)
            } else {
                parsePrimary()
            }

            while (true) {
                when {
                    matchText(".") -> {
                        val propName = next().text
                        expr = Expr.Member(expr, Expr.Literal(EsValue.Str(propName)), computed = false)
                    }
                    matchText("[") -> {
                        val idx = parseExpr()
                        expectText("]")
                        expr = Expr.Member(expr, idx, computed = true)
                    }
                    peek().text == "(" -> {
                        val args = parseArgList()
                        expr = Expr.Call(expr, args, isNew = false)
                    }
                    else -> break
                }
            }
            return expr
        }

        private fun parsePrimaryMemberOnly(): Expr {
            var expr = parsePrimary()
            while (true) {
                when {
                    matchText(".") -> {
                        val propName = next().text
                        expr = Expr.Member(expr, Expr.Literal(EsValue.Str(propName)), computed = false)
                    }
                    matchText("[") -> {
                        val idx = parseExpr()
                        expectText("]")
                        expr = Expr.Member(expr, idx, computed = true)
                    }
                    else -> break
                }
            }
            return expr
        }

        private fun parseArgList(): List<Expr> {
            expectText("(")
            val args = ArrayList<Expr>()
            if (!matchText(")")) {
                do {
                    if (peek().text == ")") break
                    args.add(parseAssign())
                } while (matchText(","))
                expectText(")")
            }
            return args
        }

        private fun parsePrimary(): Expr {
            val t = peek()
            when (t.kind) {
                TokKind.NUM -> {
                    pos++
                    val v = if (t.text.startsWith("0x", ignoreCase = true)) {
                        t.text.substring(2).toLongOrNull(16)?.toDouble() ?: Double.NaN
                    } else {
                        t.text.toDoubleOrNull() ?: Double.NaN
                    }
                    return Expr.Literal(EsValue.Num(v))
                }
                TokKind.STR -> {
                    pos++
                    return Expr.Literal(EsValue.Str(t.text))
                }
                TokKind.IDENT -> {
                    pos++
                    return when (t.text) {
                        "true" -> Expr.Literal(EsValue.Bool(true))
                        "false" -> Expr.Literal(EsValue.Bool(false))
                        "null" -> Expr.Literal(EsValue.Null)
                        "undefined" -> Expr.Literal(EsValue.Undefined)
                        "this" -> Expr.ThisExpr
                        "function" -> {
                            val fnName = if (peek().kind == TokKind.IDENT) next().text else null
                            val params = parseParamList()
                            val body = parseBlockBody()
                            Expr.FnExpr(fnName, params, body)
                        }
                        else -> Expr.Ident(t.text)
                    }
                }
                TokKind.PUNCT -> {
                    when (t.text) {
                        "(" -> {
                            pos++
                            val e = parseExpr()
                            expectText(")")
                            return e
                        }
                        "[" -> {
                            pos++
                            val items = ArrayList<Expr>()
                            if (!matchText("]")) {
                                do {
                                    if (peek().text == "]") break
                                    items.add(parseAssign())
                                } while (matchText(","))
                                expectText("]")
                            }
                            return Expr.ArrayLit(items)
                        }
                        "{" -> {
                            pos++
                            val entries = ArrayList<Pair<String, Expr>>()
                            if (!matchText("}")) {
                                do {
                                    if (peek().text == "}") break
                                    val keyTok = next()
                                    val key = keyTok.text
                                    expectText(":")
                                    val v = parseAssign()
                                    entries.add(key to v)
                                } while (matchText(","))
                                expectText("}")
                            }
                            return Expr.ObjectLit(entries)
                        }
                    }
                }
                else -> Unit
            }
            pos++
            return Expr.Literal(EsValue.Undefined)
        }
    }

    // =========================================================================
    // Evaluator
    // =========================================================================

    private sealed interface Signal {
        object None : Signal
        object Break : Signal
        object Continue : Signal
        data class Return(val value: EsValue) : Signal
    }

    private inner class ScriptFn(
        override val fnName: String,
        private val params: List<String>,
        private val body: List<Stmt>,
        private val closureScope: EsScope
    ) : EsValue.Obj(className = "Function"), EsValue.Callable {
        override val prototypeClassName: String = fnName.ifBlank { "Object" }

        override fun call(thisArg: EsValue, args: List<EsValue>, deadlineNs: Long): EsValue {
            checkDeadline(deadlineNs)
            val fnScope = EsScope(closureScope)
            if (fnName.isNotEmpty()) fnScope.declareVar(fnName, this)
            fnScope.declareVar("arguments", EsValue.Arr(args.toMutableList()))
            for (i in params.indices) {
                fnScope.declareVar(params[i], args.getOrElse(i) { EsValue.Undefined })
            }
            hoistDeclarations(body, fnScope)
            val sig = execStmtList(body, fnScope, thisArg, deadlineNs).first
            return if (sig is Signal.Return) sig.value else EsValue.Undefined
        }

        override fun construct(args: List<EsValue>, deadlineNs: Long): EsValue {
            val instance = EsValue.Obj(className = prototypeClassName)
            val res = call(instance, args, deadlineNs)
            return if (res is EsValue.Obj) res else instance
        }
    }

    private fun checkDeadline(deadlineNs: Long) {
        if (System.nanoTime() > deadlineNs) {
            throw EsTimeoutException()
        }
    }

    private fun hoistDeclarations(stmts: List<Stmt>, scope: EsScope) {
        for (stmt in stmts) {
            when (stmt) {
                is Stmt.FnDecl -> scope.vars[stmt.name] = ScriptFn(stmt.name, stmt.params, stmt.body, scope)
                is Stmt.VarDecl -> stmt.decls.forEach { (nm, _) -> if (!scope.vars.containsKey(nm)) scope.vars[nm] = EsValue.Undefined }
                is Stmt.Block -> hoistDeclarations(stmt.stmts, scope)
                is Stmt.If -> {
                    hoistSingle(stmt.thenBranch, scope)
                    stmt.elseBranch?.let { hoistSingle(it, scope) }
                }
                is Stmt.For -> {
                    stmt.init?.let { hoistSingle(it, scope) }
                    hoistSingle(stmt.body, scope)
                }
                is Stmt.ForIn -> {
                    if (!scope.vars.containsKey(stmt.varName)) scope.vars[stmt.varName] = EsValue.Undefined
                    hoistSingle(stmt.body, scope)
                }
                is Stmt.While -> hoistSingle(stmt.body, scope)
                is Stmt.DoWhile -> hoistSingle(stmt.body, scope)
                is Stmt.TryCatch -> {
                    hoistDeclarations(stmt.tryBlock, scope)
                    stmt.catchBlock?.let { hoistDeclarations(it, scope) }
                    stmt.finallyBlock?.let { hoistDeclarations(it, scope) }
                }
                else -> Unit
            }
        }
    }

    private fun hoistSingle(stmt: Stmt, scope: EsScope) = hoistDeclarations(listOf(stmt), scope)

    private fun executeBlockInScope(stmts: List<Stmt>, scope: EsScope, thisVal: EsValue, deadlineNs: Long): EsValue {
        hoistDeclarations(stmts, scope)
        val (sig, lastVal) = execStmtList(stmts, scope, thisVal, deadlineNs)
        return if (sig is Signal.Return) sig.value else lastVal
    }

    private fun execStmtList(stmts: List<Stmt>, scope: EsScope, thisVal: EsValue, deadlineNs: Long): Pair<Signal, EsValue> {
        var lastVal: EsValue = EsValue.Undefined
        for (s in stmts) {
            val (sig, v) = execStmt(s, scope, thisVal, deadlineNs)
            if (v !== EsValue.Undefined) lastVal = v
            if (sig !is Signal.None) return sig to lastVal
        }
        return Signal.None to lastVal
    }

    private fun execStmt(stmt: Stmt, scope: EsScope, thisVal: EsValue, deadlineNs: Long): Pair<Signal, EsValue> {
        checkDeadline(deadlineNs)
        return when (stmt) {
            Stmt.Empty, is Stmt.FnDecl -> Signal.None to EsValue.Undefined
            is Stmt.Block -> execStmtList(stmt.stmts, scope, thisVal, deadlineNs)
            is Stmt.VarDecl -> {
                for ((nm, init) in stmt.decls) {
                    if (init != null) {
                        scope.set(nm, evalExpr(init, scope, thisVal, deadlineNs))
                    } else if (!scope.vars.containsKey(nm)) {
                        scope.declareVar(nm, EsValue.Undefined)
                    }
                }
                Signal.None to EsValue.Undefined
            }
            is Stmt.ExprStmt -> Signal.None to evalExpr(stmt.expr, scope, thisVal, deadlineNs)
            is Stmt.Return -> Signal.Return(stmt.expr?.let { evalExpr(it, scope, thisVal, deadlineNs) } ?: EsValue.Undefined) to EsValue.Undefined
            Stmt.Break -> Signal.Break to EsValue.Undefined
            Stmt.Continue -> Signal.Continue to EsValue.Undefined
            is Stmt.Throw -> throw EsRuntimeException(evalExpr(stmt.expr, scope, thisVal, deadlineNs))
            is Stmt.If -> {
                if (evalExpr(stmt.cond, scope, thisVal, deadlineNs).isTruthy()) {
                    execStmt(stmt.thenBranch, scope, thisVal, deadlineNs)
                } else if (stmt.elseBranch != null) {
                    execStmt(stmt.elseBranch, scope, thisVal, deadlineNs)
                } else {
                    Signal.None to EsValue.Undefined
                }
            }
            is Stmt.While -> {
                while (evalExpr(stmt.cond, scope, thisVal, deadlineNs).isTruthy()) {
                    checkDeadline(deadlineNs)
                    val (sig, _) = execStmt(stmt.body, scope, thisVal, deadlineNs)
                    if (sig is Signal.Break) break
                    if (sig is Signal.Return) return sig to EsValue.Undefined
                }
                Signal.None to EsValue.Undefined
            }
            is Stmt.DoWhile -> {
                do {
                    checkDeadline(deadlineNs)
                    val (sig, _) = execStmt(stmt.body, scope, thisVal, deadlineNs)
                    if (sig is Signal.Break) break
                    if (sig is Signal.Return) return sig to EsValue.Undefined
                } while (evalExpr(stmt.cond, scope, thisVal, deadlineNs).isTruthy())
                Signal.None to EsValue.Undefined
            }
            is Stmt.For -> {
                stmt.init?.let { execStmt(it, scope, thisVal, deadlineNs) }
                while (stmt.cond == null || evalExpr(stmt.cond, scope, thisVal, deadlineNs).isTruthy()) {
                    checkDeadline(deadlineNs)
                    val (sig, _) = execStmt(stmt.body, scope, thisVal, deadlineNs)
                    if (sig is Signal.Break) break
                    if (sig is Signal.Return) return sig to EsValue.Undefined
                    stmt.step?.let { evalExpr(it, scope, thisVal, deadlineNs) }
                }
                Signal.None to EsValue.Undefined
            }
            is Stmt.ForIn -> {
                val targetVal = evalExpr(stmt.target, scope, thisVal, deadlineNs)
                val keys = when (targetVal) {
                    is EsValue.Arr -> targetVal.elements.indices.map { it.toString() }
                    is EsValue.Obj -> targetVal.props.keys.toList()
                    else -> emptyList()
                }
                for (k in keys) {
                    checkDeadline(deadlineNs)
                    scope.set(stmt.varName, EsValue.Str(k))
                    val (sig, _) = execStmt(stmt.body, scope, thisVal, deadlineNs)
                    if (sig is Signal.Break) break
                    if (sig is Signal.Return) return sig to EsValue.Undefined
                }
                Signal.None to EsValue.Undefined
            }
            is Stmt.Switch -> {
                val discVal = evalExpr(stmt.disc, scope, thisVal, deadlineNs)
                var matched = false
                for ((caseExpr, caseStmts) in stmt.cases) {
                    if (!matched) {
                        if (caseExpr == null || strictEqual(discVal, evalExpr(caseExpr, scope, thisVal, deadlineNs))) {
                            matched = true
                        }
                    }
                    if (matched) {
                        val (sig, _) = execStmtList(caseStmts, scope, thisVal, deadlineNs)
                        if (sig is Signal.Break) break
                        if (sig is Signal.Return) return sig to EsValue.Undefined
                    }
                }
                Signal.None to EsValue.Undefined
            }
            is Stmt.TryCatch -> {
                var result: Pair<Signal, EsValue> = Signal.None to EsValue.Undefined
                try {
                    result = execStmtList(stmt.tryBlock, scope, thisVal, deadlineNs)
                } catch (timeout: EsTimeoutException) {
                    throw timeout
                } catch (esErr: EsRuntimeException) {
                    if (stmt.catchBlock != null) {
                        val catchScope = EsScope(scope)
                        if (stmt.catchVar != null) catchScope.declareVar(stmt.catchVar, esErr.thrownValue)
                        result = execStmtList(stmt.catchBlock, catchScope, thisVal, deadlineNs)
                    } else if (stmt.finallyBlock == null) {
                        throw esErr
                    }
                } catch (other: Throwable) {
                    if (stmt.catchBlock != null) {
                        val catchScope = EsScope(scope)
                        val errObj = EsValue.Obj("Error").apply {
                            val msg = other.message ?: "Error"
                            setProp("message", EsValue.Str(msg))
                            setProp("__toStringText__", EsValue.Str(msg))
                        }
                        if (stmt.catchVar != null) catchScope.declareVar(stmt.catchVar, errObj)
                        result = execStmtList(stmt.catchBlock, catchScope, thisVal, deadlineNs)
                    } else if (stmt.finallyBlock == null) {
                        throw other
                    }
                } finally {
                    stmt.finallyBlock?.let {
                        val (fSig, _) = execStmtList(it, scope, thisVal, deadlineNs)
                        if (fSig !is Signal.None) result = fSig to EsValue.Undefined
                    }
                }
                result
            }
        }
    }

    private fun evalExpr(expr: Expr, scope: EsScope, thisVal: EsValue, deadlineNs: Long): EsValue {
        checkDeadline(deadlineNs)
        return when (expr) {
            is Expr.Literal -> expr.value
            Expr.ThisExpr -> thisVal
            is Expr.Ident -> scope.get(expr.name)
            is Expr.ArrayLit -> EsValue.Arr(expr.items.map { evalExpr(it, scope, thisVal, deadlineNs) }.toMutableList())
            is Expr.ObjectLit -> {
                val obj = EsValue.Obj()
                expr.entries.forEach { (k, vExpr) -> obj.setProp(k, evalExpr(vExpr, scope, thisVal, deadlineNs)) }
                obj
            }
            is Expr.FnExpr -> ScriptFn(expr.name.orEmpty(), expr.params, expr.body, scope)
            is Expr.Sequence -> {
                var last: EsValue = EsValue.Undefined
                expr.exprs.forEach { last = evalExpr(it, scope, thisVal, deadlineNs) }
                last
            }
            is Expr.Conditional -> {
                if (evalExpr(expr.cond, scope, thisVal, deadlineNs).isTruthy()) {
                    evalExpr(expr.ifTrue, scope, thisVal, deadlineNs)
                } else {
                    evalExpr(expr.ifFalse, scope, thisVal, deadlineNs)
                }
            }
            is Expr.Member -> {
                val target = evalExpr(expr.obj, scope, thisVal, deadlineNs)
                val propKey = evalExpr(expr.prop, scope, thisVal, deadlineNs).toJsString()
                readMember(target, propKey)
            }
            is Expr.Call -> {
                val args = expr.args.map { evalExpr(it, scope, thisVal, deadlineNs) }
                if (expr.isNew) {
                    val ctor = evalExpr(expr.callee, scope, thisVal, deadlineNs)
                    val callable = ctor as? EsValue.Callable
                        ?: throw EsRuntimeException(EsValue.Str("Value is not a constructor"))
                    callable.construct(args, deadlineNs)
                } else if (expr.callee is Expr.Member) {
                    val receiver = evalExpr(expr.callee.obj, scope, thisVal, deadlineNs)
                    val key = evalExpr(expr.callee.prop, scope, thisVal, deadlineNs).toJsString()
                    val fnVal = readMember(receiver, key)
                    val callable = fnVal as? EsValue.Callable
                        ?: throw EsRuntimeException(EsValue.Str("Property '$key' is not a function"))
                    callable.call(receiver, args, deadlineNs)
                } else {
                    val fnVal = evalExpr(expr.callee, scope, thisVal, deadlineNs)
                    val callable = fnVal as? EsValue.Callable
                        ?: throw EsRuntimeException(EsValue.Str("Expression is not a function"))
                    callable.call(globalObject, args, deadlineNs)
                }
            }
            is Expr.Unary -> evalUnary(expr, scope, thisVal, deadlineNs)
            is Expr.Binary -> evalBinary(expr, scope, thisVal, deadlineNs)
            is Expr.Assign -> evalAssign(expr, scope, thisVal, deadlineNs)
        }
    }

    private fun evalUnary(expr: Expr.Unary, scope: EsScope, thisVal: EsValue, deadlineNs: Long): EsValue {
        if (expr.op == "typeof") {
            val v = if (expr.operand is Expr.Ident) scope.get(expr.operand.name) else evalExpr(expr.operand, scope, thisVal, deadlineNs)
            val t = when (v) {
                EsValue.Undefined -> "undefined"
                is EsValue.Bool -> "boolean"
                is EsValue.Num -> "number"
                is EsValue.Str -> "string"
                is EsValue.Callable -> "function"
                EsValue.Null, is EsValue.Obj -> "object"
            }
            return EsValue.Str(t)
        }
        if (expr.op == "void") {
            evalExpr(expr.operand, scope, thisVal, deadlineNs)
            return EsValue.Undefined
        }
        if (expr.op == "delete") {
            if (expr.operand is Expr.Member) {
                val o = evalExpr(expr.operand.obj, scope, thisVal, deadlineNs) as? EsValue.Obj
                val k = evalExpr(expr.operand.prop, scope, thisVal, deadlineNs).toJsString()
                o?.props?.remove(k)
            }
            return EsValue.Bool(true)
        }
        if (expr.op == "++" || expr.op == "--") {
            val delta = if (expr.op == "++") 1.0 else -1.0
            val oldVal = evalExpr(expr.operand, scope, thisVal, deadlineNs).toNum()
            val newVal = EsValue.Num(oldVal + delta)
            writeTarget(expr.operand, newVal, scope, thisVal, deadlineNs)
            return if (expr.prefix) newVal else EsValue.Num(oldVal)
        }
        val v = evalExpr(expr.operand, scope, thisVal, deadlineNs)
        return when (expr.op) {
            "!" -> EsValue.Bool(!v.isTruthy())
            "-" -> EsValue.Num(-v.toNum())
            "+" -> EsValue.Num(v.toNum())
            "~" -> EsValue.Num((v.toNum().toInt().inv()).toDouble())
            else -> EsValue.Undefined
        }
    }

    private fun evalBinary(expr: Expr.Binary, scope: EsScope, thisVal: EsValue, deadlineNs: Long): EsValue {
        if (expr.op == "&&") {
            val l = evalExpr(expr.left, scope, thisVal, deadlineNs)
            return if (!l.isTruthy()) l else evalExpr(expr.right, scope, thisVal, deadlineNs)
        }
        if (expr.op == "||") {
            val l = evalExpr(expr.left, scope, thisVal, deadlineNs)
            return if (l.isTruthy()) l else evalExpr(expr.right, scope, thisVal, deadlineNs)
        }
        val l = evalExpr(expr.left, scope, thisVal, deadlineNs)
        val r = evalExpr(expr.right, scope, thisVal, deadlineNs)
        return when (expr.op) {
            "+" -> {
                if (l is EsValue.Str || r is EsValue.Str) EsValue.Str(l.toJsString() + r.toJsString())
                else EsValue.Num(l.toNum() + r.toNum())
            }
            "-" -> EsValue.Num(l.toNum() - r.toNum())
            "*" -> EsValue.Num(l.toNum() * r.toNum())
            "/" -> EsValue.Num(l.toNum() / r.toNum())
            "%" -> EsValue.Num(l.toNum() % r.toNum())
            "===" -> EsValue.Bool(strictEqual(l, r))
            "!==" -> EsValue.Bool(!strictEqual(l, r))
            "==" -> EsValue.Bool(looseEqual(l, r))
            "!=" -> EsValue.Bool(!looseEqual(l, r))
            "<" -> EsValue.Bool(compareValues(l, r) < 0)
            "<=" -> EsValue.Bool(compareValues(l, r) <= 0)
            ">" -> EsValue.Bool(compareValues(l, r) > 0)
            ">=" -> EsValue.Bool(compareValues(l, r) >= 0)
            "&" -> EsValue.Num((l.toNum().toInt() and r.toNum().toInt()).toDouble())
            "|" -> EsValue.Num((l.toNum().toInt() or r.toNum().toInt()).toDouble())
            "^" -> EsValue.Num((l.toNum().toInt() xor r.toNum().toInt()).toDouble())
            "<<" -> EsValue.Num((l.toNum().toInt() shl (r.toNum().toInt() and 31)).toDouble())
            ">>" -> EsValue.Num((l.toNum().toInt() shr (r.toNum().toInt() and 31)).toDouble())
            ">>>" -> EsValue.Num((l.toNum().toInt() ushr (r.toNum().toInt() and 31)).toDouble())
            "instanceof" -> {
                val obj = l as? EsValue.Obj ?: return EsValue.Bool(false)
                val ctorName = (r as? EsValue.Callable)?.prototypeClassName
                    ?: (r as? EsValue.Obj)?.getProp("name")?.toJsString()
                    ?: ""
                val matches = obj.className == ctorName ||
                    (ctorName == "Array" && obj is EsValue.Arr) ||
                    (ctorName == "Object") ||
                    (ctorName == "Panel" && obj.className in setOf("Panel", "Window")) ||
                    (ctorName == "AVLayer" && obj.className in setOf("AVLayer", "TextLayer", "ShapeLayer"))
                EsValue.Bool(matches)
            }
            "in" -> {
                val key = l.toJsString()
                val exists = when (r) {
                    is EsValue.Arr -> key == "length" || (key.toIntOrNull()?.let { it in r.elements.indices } == true) || r.props.containsKey(key)
                    is EsValue.Obj -> r.getProp(key) !== EsValue.Undefined || r.props.containsKey(key)
                    else -> false
                }
                EsValue.Bool(exists)
            }
            else -> EsValue.Undefined
        }
    }

    private fun evalAssign(expr: Expr.Assign, scope: EsScope, thisVal: EsValue, deadlineNs: Long): EsValue {
        val rhs = evalExpr(expr.value, scope, thisVal, deadlineNs)
        if (expr.op == "=") {
            writeTarget(expr.target, rhs, scope, thisVal, deadlineNs)
            return rhs
        }
        val cur = evalExpr(expr.target, scope, thisVal, deadlineNs)
        val combined = when (expr.op) {
            "+=" -> if (cur is EsValue.Str || rhs is EsValue.Str) EsValue.Str(cur.toJsString() + rhs.toJsString()) else EsValue.Num(cur.toNum() + rhs.toNum())
            "-=" -> EsValue.Num(cur.toNum() - rhs.toNum())
            "*=" -> EsValue.Num(cur.toNum() * rhs.toNum())
            "/=" -> EsValue.Num(cur.toNum() / rhs.toNum())
            "%=" -> EsValue.Num(cur.toNum() % rhs.toNum())
            else -> rhs
        }
        writeTarget(expr.target, combined, scope, thisVal, deadlineNs)
        return combined
    }

    private fun writeTarget(target: Expr, value: EsValue, scope: EsScope, thisVal: EsValue, deadlineNs: Long) {
        when (target) {
            is Expr.Ident -> scope.set(target.name, value)
            is Expr.Member -> {
                val obj = evalExpr(target.obj, scope, thisVal, deadlineNs) as? EsValue.Obj ?: return
                val propKey = evalExpr(target.prop, scope, thisVal, deadlineNs).toJsString()
                obj.setProp(propKey, value)
            }
            else -> Unit
        }
    }

    private fun readMember(target: EsValue, propKey: String): EsValue {
        if (target is EsValue.Str) return readStringMember(target.value, propKey)
        if (target is EsValue.Num) return readNumberMember(target.value, propKey)
        if (target is EsValue.Arr) return readArrayMember(target, propKey)
        if (target is EsValue.Obj) {
            if (propKey == "toString" && !target.props.containsKey("toString")) {
                return EsValue.NativeFn("toString") { recv, _, _ -> EsValue.Str(recv.toJsString()) }
            }
            if (propKey == "hasOwnProperty") {
                return EsValue.NativeFn("hasOwnProperty") { recv, a, _ ->
                    val k = a.firstOrNull()?.toJsString().orEmpty()
                    EsValue.Bool((recv as? EsValue.Obj)?.props?.containsKey(k) == true)
                }
            }
            return target.getProp(propKey)
        }
        return EsValue.Undefined
    }

    private fun readStringMember(s: String, propKey: String): EsValue = when (propKey) {
        "length" -> EsValue.Num(s.length.toDouble())
        "slice", "substring" -> EsValue.NativeFn(propKey) { _, a, _ ->
            val rawStart = a.getOrNull(0)?.toNum()?.toInt() ?: 0
            val start = (if (rawStart < 0) s.length + rawStart else rawStart).coerceIn(0, s.length)
            val rawEnd = a.getOrNull(1)?.takeIf { it !== EsValue.Undefined }?.toNum()?.toInt() ?: s.length
            val end = (if (rawEnd < 0 && propKey == "slice") s.length + rawEnd else rawEnd).coerceIn(start, s.length)
            EsValue.Str(s.substring(start, end))
        }
        "substr" -> EsValue.NativeFn("substr") { _, a, _ ->
            val start = (a.getOrNull(0)?.toNum()?.toInt() ?: 0).let { if (it < 0) s.length + it else it }.coerceIn(0, s.length)
            val len = (a.getOrNull(1)?.toNum()?.toInt() ?: (s.length - start)).coerceAtLeast(0)
            EsValue.Str(s.substring(start, (start + len).coerceAtMost(s.length)))
        }
        "indexOf" -> EsValue.NativeFn("indexOf") { _, a, _ ->
            val sub = a.firstOrNull()?.toJsString().orEmpty()
            val from = a.getOrNull(1)?.toNum()?.toInt()?.coerceAtLeast(0) ?: 0
            EsValue.Num(s.indexOf(sub, from).toDouble())
        }
        "lastIndexOf" -> EsValue.NativeFn("lastIndexOf") { _, a, _ ->
            val sub = a.firstOrNull()?.toJsString().orEmpty()
            EsValue.Num(s.lastIndexOf(sub).toDouble())
        }
        "toLowerCase" -> EsValue.NativeFn("toLowerCase") { _, _, _ -> EsValue.Str(s.lowercase()) }
        "toUpperCase" -> EsValue.NativeFn("toUpperCase") { _, _, _ -> EsValue.Str(s.uppercase()) }
        "trim" -> EsValue.NativeFn("trim") { _, _, _ -> EsValue.Str(s.trim()) }
        "split" -> EsValue.NativeFn("split") { _, a, _ ->
            val sep = a.firstOrNull()?.toJsString().orEmpty()
            val parts = if (sep.isEmpty()) s.map { it.toString() } else s.split(sep)
            EsValue.Arr(parts.map { EsValue.Str(it) }.toMutableList())
        }
        "replace" -> EsValue.NativeFn("replace") { _, a, _ ->
            val targetStr = a.getOrNull(0)?.toJsString().orEmpty()
            val repl = a.getOrNull(1)?.toJsString().orEmpty()
            EsValue.Str(s.replaceFirst(targetStr, repl))
        }
        "charAt" -> EsValue.NativeFn("charAt") { _, a, _ ->
            val idx = a.firstOrNull()?.toNum()?.toInt() ?: 0
            EsValue.Str(if (idx in s.indices) s[idx].toString() else "")
        }
        "charCodeAt" -> EsValue.NativeFn("charCodeAt") { _, a, _ ->
            val idx = a.firstOrNull()?.toNum()?.toInt() ?: 0
            EsValue.Num(if (idx in s.indices) s[idx].code.toDouble() else Double.NaN)
        }
        "toString" -> EsValue.NativeFn("toString") { _, _, _ -> EsValue.Str(s) }
        else -> {
            val idx = propKey.toIntOrNull()
            if (idx != null && idx in s.indices) EsValue.Str(s[idx].toString()) else EsValue.Undefined
        }
    }

    private fun readNumberMember(v: Double, propKey: String): EsValue = when (propKey) {
        "toFixed" -> EsValue.NativeFn("toFixed") { _, a, _ ->
            val digits = (a.firstOrNull()?.toNum()?.toInt() ?: 0).coerceIn(0, 12)
            EsValue.Str("%.${digits}f".format(java.util.Locale.US, v))
        }
        "toString" -> EsValue.NativeFn("toString") { _, _, _ -> EsValue.Str(EsValue.Num(v).toString()) }
        else -> EsValue.Undefined
    }

    private fun readArrayMember(arr: EsValue.Arr, propKey: String): EsValue = when (propKey) {
        "push" -> EsValue.NativeFn("push") { _, a, _ ->
            arr.elements.addAll(a)
            EsValue.Num(arr.elements.size.toDouble())
        }
        "pop" -> EsValue.NativeFn("pop") { _, _, _ ->
            if (arr.elements.isEmpty()) EsValue.Undefined else arr.elements.removeAt(arr.elements.lastIndex)
        }
        "shift" -> EsValue.NativeFn("shift") { _, _, _ ->
            if (arr.elements.isEmpty()) EsValue.Undefined else arr.elements.removeAt(0)
        }
        "unshift" -> EsValue.NativeFn("unshift") { _, a, _ ->
            arr.elements.addAll(0, a)
            EsValue.Num(arr.elements.size.toDouble())
        }
        "slice" -> EsValue.NativeFn("slice") { _, a, _ ->
            val n = arr.elements.size
            val rs = a.getOrNull(0)?.toNum()?.toInt() ?: 0
            val start = (if (rs < 0) n + rs else rs).coerceIn(0, n)
            val re = a.getOrNull(1)?.takeIf { it !== EsValue.Undefined }?.toNum()?.toInt() ?: n
            val end = (if (re < 0) n + re else re).coerceIn(start, n)
            EsValue.Arr(arr.elements.subList(start, end).toMutableList())
        }
        "join" -> EsValue.NativeFn("join") { _, a, _ ->
            val sep = a.firstOrNull()?.takeIf { it !== EsValue.Undefined }?.toJsString() ?: ","
            EsValue.Str(arr.elements.joinToString(sep) { if (it === EsValue.Undefined || it === EsValue.Null) "" else it.toJsString() })
        }
        "indexOf" -> EsValue.NativeFn("indexOf") { _, a, _ ->
            val needle = a.firstOrNull() ?: EsValue.Undefined
            EsValue.Num(arr.elements.indexOfFirst { strictEqual(it, needle) }.toDouble())
        }
        "concat" -> EsValue.NativeFn("concat") { _, a, _ ->
            val res = arr.elements.toMutableList()
            a.forEach { if (it is EsValue.Arr) res.addAll(it.elements) else res.add(it) }
            EsValue.Arr(res)
        }
        "map" -> EsValue.NativeFn("map") { _, a, dl ->
            val fn = a.firstOrNull() as? EsValue.Callable ?: return@NativeFn EsValue.Arr()
            EsValue.Arr(arr.elements.mapIndexed { idx, item -> fn.call(globalObject, listOf(item, EsValue.Num(idx.toDouble()), arr), dl) }.toMutableList())
        }
        "forEach" -> EsValue.NativeFn("forEach") { _, a, dl ->
            val fn = a.firstOrNull() as? EsValue.Callable ?: return@NativeFn EsValue.Undefined
            arr.elements.forEachIndexed { idx, item -> fn.call(globalObject, listOf(item, EsValue.Num(idx.toDouble()), arr), dl) }
            EsValue.Undefined
        }
        "filter" -> EsValue.NativeFn("filter") { _, a, dl ->
            val fn = a.firstOrNull() as? EsValue.Callable ?: return@NativeFn EsValue.Arr()
            val out = ArrayList<EsValue>()
            arr.elements.forEachIndexed { idx, item ->
                if (fn.call(globalObject, listOf(item, EsValue.Num(idx.toDouble()), arr), dl).isTruthy()) out.add(item)
            }
            EsValue.Arr(out)
        }
        else -> arr.getProp(propKey)
    }

    private fun strictEqual(a: EsValue, b: EsValue): Boolean = when {
        a === b -> true
        a is EsValue.Num && b is EsValue.Num -> !a.value.isNaN() && !b.value.isNaN() && a.value == b.value
        a is EsValue.Str && b is EsValue.Str -> a.value == b.value
        a is EsValue.Bool && b is EsValue.Bool -> a.value == b.value
        else -> false
    }

    private fun looseEqual(a: EsValue, b: EsValue): Boolean {
        if (strictEqual(a, b)) return true
        if ((a === EsValue.Null && b === EsValue.Undefined) || (a === EsValue.Undefined && b === EsValue.Null)) return true
        if ((a is EsValue.Num || a is EsValue.Bool) && (b is EsValue.Str || b is EsValue.Bool || b is EsValue.Num)) {
            return a.toNum() == b.toNum()
        }
        if (a is EsValue.Str && (b is EsValue.Num || b is EsValue.Bool)) {
            return a.toNum() == b.toNum()
        }
        return false
    }

    private fun compareValues(a: EsValue, b: EsValue): Int {
        if (a is EsValue.Str && b is EsValue.Str) return a.value.compareTo(b.value)
        return a.toNum().compareTo(b.toNum())
    }
}
