"""A small Python subset for robot programs, run by walking the `ast` of the source.

The model writes ordinary-looking Python: assignments, arithmetic, if/while/for, try/except, f-strings,
lists and dicts, and calls to the robot functions it is given. Nothing else exists: no imports, no
attribute access except a few list, dict and string methods, no definitions, no dunder names. Every
statement costs an operation and every robot call a step, both with a budget; a deadline and a cancel
event end the program at the next statement."""

from __future__ import annotations

import ast
import threading
import time
from typing import Any, Callable, Optional


class RobotError(Exception):
    """A robot call failed. Programs may catch it with `except RobotError as e` (e is {code, message})."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message

    def as_value(self) -> dict[str, str]:
        return {"code": self.code, "message": self.message}


class ProgramHalt(Exception):
    """The program ended without a return: `kind` is error, budget, timeout, aborted or stopped."""

    def __init__(self, kind: str, message: str, line: Optional[int] = None) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message
        self.line = line


class _Return(Exception):
    def __init__(self, value: Any) -> None:
        super().__init__()
        self.value = value


class _Break(Exception):
    pass


class _Continue(Exception):
    pass


# Statements a program may run.
MAX_OPS = 200_000

_STATEMENTS = (
    ast.Expr, ast.Assign, ast.AugAssign, ast.If, ast.While, ast.For, ast.Break, ast.Continue, ast.Pass,
    ast.Return, ast.Try,
)
_EXPRESSIONS = (
    ast.Constant, ast.Name, ast.BinOp, ast.UnaryOp, ast.BoolOp, ast.Compare, ast.Call, ast.Subscript,
    ast.Slice, ast.IfExp, ast.List, ast.Tuple, ast.Dict, ast.JoinedStr, ast.FormattedValue, ast.ListComp,
    ast.Attribute,
)

_BIN_OPS: dict[type, Callable[[Any, Any], Any]] = {
    ast.Add: lambda a, b: a + b,
    ast.Sub: lambda a, b: a - b,
    ast.Mult: lambda a, b: a * b,
    ast.Div: lambda a, b: a / b,
    ast.FloorDiv: lambda a, b: a // b,
    ast.Mod: lambda a, b: a % b,
    ast.Pow: lambda a, b: a ** b if abs(b) <= 64 else _too_big(),
}
_COMPARE_OPS: dict[type, Callable[[Any, Any], bool]] = {
    ast.Eq: lambda a, b: a == b,
    ast.NotEq: lambda a, b: a != b,
    ast.Lt: lambda a, b: a < b,
    ast.LtE: lambda a, b: a <= b,
    ast.Gt: lambda a, b: a > b,
    ast.GtE: lambda a, b: a >= b,
    ast.In: lambda a, b: a in b,
    ast.NotIn: lambda a, b: a not in b,
    ast.Is: lambda a, b: a is b,
    ast.IsNot: lambda a, b: a is not b,
}

# Methods a program may call on values, by the value's type.
_METHODS: dict[type, set[str]] = {
    str: {"lower", "upper", "startswith", "endswith", "split", "strip", "replace", "join", "find", "count", "isdigit", "format"},
    list: {"append", "extend", "pop", "insert", "remove", "index", "count", "sort", "reverse", "clear", "copy"},
    dict: {"get", "keys", "values", "items", "pop", "update", "setdefault", "copy"},
    tuple: {"index", "count"},
}

_BUILTINS: dict[str, Any] = {
    "abs": abs, "min": min, "max": max, "len": len, "round": round, "int": int, "float": float, "str": str,
    "bool": bool, "list": list, "dict": dict, "tuple": tuple, "range": range, "enumerate": enumerate, "zip": zip,
    "sorted": sorted, "reversed": reversed, "sum": sum, "any": any, "all": all,
    "True": True, "False": False, "None": None,
}


def _too_big() -> Any:
    raise ValueError("exponent too large")


class Program:
    """One program run. `api` maps robot function names to callables (each call is a step); `helpers` maps
    extra function names (log, sleep, now) to callables that cost no step."""

    def __init__(
        self,
        source: str,
        api: dict[str, Callable[..., Any]],
        helpers: dict[str, Callable[..., Any]],
        *,
        max_steps: int,
        deadline: float,
        cancel: Optional[threading.Event] = None,
        on_step: Optional[Callable[[str, Any], None]] = None,
    ) -> None:
        self.source = source
        self.api = api
        self.helpers = helpers
        self.max_steps = max_steps
        self.deadline = deadline
        self.cancel = cancel
        self.on_step = on_step
        self.steps = 0
        self.ops = 0
        self.line = 0
        self.last_call: Optional[str] = None
        self.vars: dict[str, Any] = {}

    # -- entry ---------------------------------------------------------------------------------
    def run(self) -> Any:
        try:
            tree = ast.parse(self.source, mode="exec")
        except SyntaxError as exc:
            raise ProgramHalt("error", f"syntax error: {exc.msg}", exc.lineno) from None
        self._check(tree)
        try:
            self._exec_body(tree.body)
        except _Return as ret:
            return ret.value
        except RobotError as exc:
            raise ProgramHalt("error", f"{exc.code}: {exc.message}", self.line) from None
        except (_Break, _Continue):
            raise ProgramHalt("error", "break or continue outside a loop", self.line) from None
        return None

    # -- static checks ---------------------------------------------------------------------------
    def _check(self, tree: ast.AST) -> None:
        for node in ast.walk(tree):
            if isinstance(node, (ast.Module, ast.Load, ast.Store, ast.Del, ast.arguments, ast.keyword, ast.comprehension,
                                 ast.ExceptHandler, ast.expr_context, ast.operator, ast.unaryop, ast.boolop, ast.cmpop)):
                continue
            if isinstance(node, _STATEMENTS) or isinstance(node, _EXPRESSIONS):
                if isinstance(node, ast.Attribute) and (node.attr.startswith("_") or not isinstance(node.ctx, ast.Load)):
                    self._refuse(node, f"attribute '{node.attr}' is not allowed")
                if isinstance(node, ast.Name) and node.id.startswith("__"):
                    self._refuse(node, f"name '{node.id}' is not allowed")
                if isinstance(node, ast.Try) and (node.orelse or node.finalbody):
                    self._refuse(node, "try/else and try/finally are not supported; use try/except")
                continue
            what = type(node).__name__
            hint = {
                "Import": "import", "ImportFrom": "import", "FunctionDef": "def", "AsyncFunctionDef": "def",
                "ClassDef": "class", "Lambda": "lambda", "With": "with", "Global": "global", "Nonlocal": "nonlocal",
                "Delete": "del", "Assert": "assert", "Raise": "raise", "Yield": "yield", "Await": "await",
                "DictComp": "dict comprehensions", "SetComp": "set comprehensions", "GeneratorExp": "generator expressions",
                "Starred": "* unpacking", "NamedExpr": ":=", "AnnAssign": "annotations", "Set": "set literals",
                "Match": "match",
            }.get(what, what)
            self._refuse(node, f"{hint} is not allowed in a robot program")

    def _refuse(self, node: ast.AST, message: str) -> None:
        raise ProgramHalt("error", message, getattr(node, "lineno", None))

    # -- statements ------------------------------------------------------------------------------
    def _exec_body(self, body: list[ast.stmt]) -> None:
        for stmt in body:
            self._exec(stmt)

    def _tick(self, node: ast.AST) -> None:
        self.line = getattr(node, "lineno", self.line)
        self.ops += 1
        if self.ops > MAX_OPS:
            raise ProgramHalt("budget", f"the program ran {MAX_OPS} statements without finishing (an endless loop?)", self.line)
        if self.cancel is not None and self.cancel.is_set():
            raise ProgramHalt("cancelled", "the call was cancelled", self.line)
        if time.monotonic() >= self.deadline:
            raise ProgramHalt("timeout", "max_seconds passed", self.line)

    def _exec(self, node: ast.stmt) -> None:
        self._tick(node)
        if isinstance(node, ast.Expr):
            self._eval(node.value)
        elif isinstance(node, ast.Assign):
            value = self._eval(node.value)
            for target in node.targets:
                self._assign(target, value)
        elif isinstance(node, ast.AugAssign):
            current = self._eval(ast.Name(id=node.target.id, ctx=ast.Load())) if isinstance(node.target, ast.Name) else self._eval(node.target)
            op = _BIN_OPS.get(type(node.op))
            if op is None:
                self._fail(f"operator {type(node.op).__name__} is not supported")
            self._assign(node.target, self._arith(op, current, self._eval(node.value)))
        elif isinstance(node, ast.If):
            self._exec_body(node.body if self._truth(self._eval(node.test)) else node.orelse)
        elif isinstance(node, ast.While):
            while self._truth(self._eval(node.test)):
                self._tick(node)
                try:
                    self._exec_body(node.body)
                except _Break:
                    break
                except _Continue:
                    continue
            else:
                self._exec_body(node.orelse)
        elif isinstance(node, ast.For):
            iterable = self._eval(node.iter)
            try:
                items = list(iterable)
            except TypeError:
                self._fail(f"cannot loop over {type(iterable).__name__}")
            broke = False
            for item in items:
                self._tick(node)
                self._assign(node.target, item)
                try:
                    self._exec_body(node.body)
                except _Break:
                    broke = True
                    break
                except _Continue:
                    continue
            if not broke:
                self._exec_body(node.orelse)
        elif isinstance(node, ast.Break):
            raise _Break()
        elif isinstance(node, ast.Continue):
            raise _Continue()
        elif isinstance(node, ast.Pass):
            pass
        elif isinstance(node, ast.Return):
            raise _Return(self._eval(node.value) if node.value is not None else None)
        elif isinstance(node, ast.Try):
            self._exec_try(node)
        else:
            self._fail(f"{type(node).__name__} is not supported")

    def _exec_try(self, node: ast.Try) -> None:
        try:
            self._exec_body(node.body)
        except RobotError as exc:
            for handler in node.handlers:
                if self._handles(handler, exc):
                    if handler.name:
                        self.vars[handler.name] = exc.as_value()
                    self._exec_body(handler.body)
                    return
            raise

    def _handles(self, handler: ast.ExceptHandler, exc: RobotError) -> bool:
        if handler.type is None:
            return True
        names = handler.type.elts if isinstance(handler.type, ast.Tuple) else [handler.type]
        for name in names:
            if not isinstance(name, ast.Name):
                self._fail("except needs RobotError, Exception or a robot error code name")
            if name.id in ("RobotError", "Exception"):
                return True
            if name.id == exc.code:
                return True
        return False

    def _assign(self, target: ast.expr, value: Any) -> None:
        if isinstance(target, ast.Name):
            if target.id in self.api or target.id in self.helpers or target.id in _BUILTINS:
                self._fail(f"'{target.id}' is a robot function and cannot be assigned")
            self.vars[target.id] = value
        elif isinstance(target, (ast.Tuple, ast.List)):
            try:
                items = list(value)
            except TypeError:
                self._fail(f"cannot unpack {type(value).__name__}")
            if len(items) != len(target.elts):
                self._fail(f"cannot unpack {len(items)} values into {len(target.elts)} names")
            for element, item in zip(target.elts, items):
                self._assign(element, item)
        elif isinstance(target, ast.Subscript):
            container = self._eval(target.value)
            key = self._eval(target.slice)
            try:
                container[key] = value
            except (TypeError, IndexError, KeyError) as exc:
                self._fail(str(exc))
        else:
            self._fail(f"cannot assign to {type(target).__name__}")

    # -- expressions -----------------------------------------------------------------------------
    def _eval(self, node: ast.expr) -> Any:
        self.line = getattr(node, "lineno", self.line)
        if isinstance(node, ast.Constant):
            return node.value
        if isinstance(node, ast.Name):
            return self._lookup(node.id)
        if isinstance(node, ast.BinOp):
            op = _BIN_OPS.get(type(node.op))
            if op is None:
                self._fail(f"operator {type(node.op).__name__} is not supported")
            return self._arith(op, self._eval(node.left), self._eval(node.right))
        if isinstance(node, ast.UnaryOp):
            value = self._eval(node.operand)
            if isinstance(node.op, ast.Not):
                return not self._truth(value)
            if isinstance(node.op, ast.USub):
                return self._arith(lambda a, _b: -a, value, None)
            if isinstance(node.op, ast.UAdd):
                return self._arith(lambda a, _b: +a, value, None)
            self._fail("operator ~ is not supported")
        if isinstance(node, ast.BoolOp):
            if isinstance(node.op, ast.And):
                result: Any = True
                for value in node.values:
                    result = self._eval(value)
                    if not self._truth(result):
                        return result
                return result
            result = False
            for value in node.values:
                result = self._eval(value)
                if self._truth(result):
                    return result
            return result
        if isinstance(node, ast.Compare):
            left = self._eval(node.left)
            for op_node, comparator in zip(node.ops, node.comparators):
                right = self._eval(comparator)
                op = _COMPARE_OPS.get(type(op_node))
                if op is None:
                    self._fail(f"comparison {type(op_node).__name__} is not supported")
                try:
                    if not op(left, right):
                        return False
                except TypeError as exc:
                    self._fail(str(exc))
                left = right
            return True
        if isinstance(node, ast.IfExp):
            return self._eval(node.body if self._truth(self._eval(node.test)) else node.orelse)
        if isinstance(node, ast.List):
            return [self._eval(e) for e in node.elts]
        if isinstance(node, ast.Tuple):
            return tuple(self._eval(e) for e in node.elts)
        if isinstance(node, ast.Dict):
            out: dict[Any, Any] = {}
            for key, value in zip(node.keys, node.values):
                if key is None:
                    self._fail("** unpacking in a dict is not allowed")
                out[self._eval(key)] = self._eval(value)
            return out
        if isinstance(node, ast.JoinedStr):
            return "".join(str(self._eval(part)) for part in node.values)
        if isinstance(node, ast.FormattedValue):
            value = self._eval(node.value)
            if node.conversion == ord("r"):
                value = repr(value)
            elif node.conversion == ord("s"):
                value = str(value)
            spec = self._eval(node.format_spec) if node.format_spec is not None else ""
            try:
                return format(value, spec)
            except (ValueError, TypeError) as exc:
                self._fail(str(exc))
        if isinstance(node, ast.Subscript):
            container = self._eval(node.value)
            if isinstance(node.slice, ast.Slice):
                lower = self._eval(node.slice.lower) if node.slice.lower is not None else None
                upper = self._eval(node.slice.upper) if node.slice.upper is not None else None
                step = self._eval(node.slice.step) if node.slice.step is not None else None
                key: Any = slice(lower, upper, step)
            else:
                key = self._eval(node.slice)
            try:
                return container[key]
            except (KeyError, IndexError, TypeError) as exc:
                self._fail(f"{type(exc).__name__}: {exc}")
        if isinstance(node, ast.ListComp):
            return self._list_comp(node)
        if isinstance(node, ast.Call):
            return self._call(node)
        if isinstance(node, ast.Attribute):
            self._fail(f"attribute access '.{node.attr}' is not allowed; results are dicts, use ['{node.attr}']")
        self._fail(f"{type(node).__name__} is not supported")

    def _list_comp(self, node: ast.ListComp) -> list[Any]:
        if len(node.generators) != 1:
            self._fail("only one `for` is allowed in a list comprehension")
        gen = node.generators[0]
        saved = dict(self.vars)
        out = []
        try:
            items = list(self._eval(gen.iter))
        except TypeError:
            self._fail("cannot loop over that in a list comprehension")
        for item in items:
            self._tick(node)
            self._assign(gen.target, item)
            if all(self._truth(self._eval(cond)) for cond in gen.ifs):
                out.append(self._eval(node.elt))
        self.vars = saved
        return out

    def _call(self, node: ast.Call) -> Any:
        args = [self._eval(a) for a in node.args]
        kwargs: dict[str, Any] = {}
        for keyword in node.keywords:
            if keyword.arg is None:
                self._fail("** unpacking in a call is not allowed")
            kwargs[keyword.arg] = self._eval(keyword.value)

        if isinstance(node.func, ast.Attribute):
            target = self._eval(node.func.value)
            allowed = _METHODS.get(type(target), set())
            if node.func.attr not in allowed:
                self._fail(f"{type(target).__name__} has no method '{node.func.attr}' in a robot program")
            try:
                return getattr(target, node.func.attr)(*args, **kwargs)
            except (TypeError, ValueError, KeyError, IndexError, AttributeError) as exc:
                self._fail(f"{type(exc).__name__}: {exc}")

        if not isinstance(node.func, ast.Name):
            self._fail("only named functions can be called")
        name = node.func.id
        if name in self.api:
            return self._robot_call(name, args, kwargs)
        if name in self.helpers:
            try:
                return self.helpers[name](*args, **kwargs)
            except RobotError:
                raise
            except (TypeError, ValueError) as exc:
                self._fail(f"{name}: {exc}")
        if name in _BUILTINS and callable(_BUILTINS[name]):
            try:
                return _BUILTINS[name](*args, **kwargs)
            except (TypeError, ValueError, ZeroDivisionError) as exc:
                self._fail(f"{name}: {exc}")
        if name in self.vars:
            self._fail(f"'{name}' is a value, not a function")
        self._fail(f"unknown function '{name}'")

    def _robot_call(self, name: str, args: list[Any], kwargs: dict[str, Any]) -> Any:
        if self.steps >= self.max_steps:
            raise ProgramHalt("budget", f"the program used its {self.max_steps} robot calls (max_steps)", self.line)
        self.steps += 1
        self.last_call = name
        try:
            result = self.api[name](*args, **kwargs)
        except TypeError as exc:
            self._fail(f"{name}: {exc}")
        if self.on_step is not None:
            self.on_step(name, result)
        return result

    # -- helpers ---------------------------------------------------------------------------------
    def _lookup(self, name: str) -> Any:
        if name in self.vars:
            return self.vars[name]
        if name in _BUILTINS:
            return _BUILTINS[name]
        if name in self.api or name in self.helpers:
            self._fail(f"'{name}' is a function: call it")
        self._fail(f"unknown name '{name}'")

    def _arith(self, op: Callable[[Any, Any], Any], a: Any, b: Any) -> Any:
        try:
            return op(a, b)
        except (TypeError, ValueError, ZeroDivisionError, OverflowError) as exc:
            self._fail(f"{type(exc).__name__}: {exc}")

    @staticmethod
    def _truth(value: Any) -> bool:
        return bool(value)

    def _fail(self, message: str) -> None:
        raise ProgramHalt("error", message, self.line)
