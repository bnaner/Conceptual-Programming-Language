package plc.project.evaluator;

import org.w3c.dom.events.EventException;
import plc.project.Main;
import plc.project.analyzer.Type;
import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class Evaluator implements Ast.Visitor<RuntimeValue, EvaluateException> {

    private Scope scope;

    public Evaluator(Scope scope) {
        this.scope = scope;
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public RuntimeValue visit(Ast.Source ast) throws EvaluateException {
        RuntimeValue value = new RuntimeValue.Primitive(null);
        try {
            for (var stmt : ast.statements()) {
                value = visit(stmt);
            }
            return value;
        } catch (EvaluateReturn e) {
            throw new EvaluateException("RETURN called outside of a function", e.stmt);
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Let ast) throws EvaluateException {
        if (scope.get(ast.name()).isPresent())
            throw new EvaluateException("Variable is already declared in scope", ast);
        var value = ast.value();
        RuntimeValue assignment = new RuntimeValue.Primitive(null);
        if (value.isPresent())
            assignment = visit(value.get());
        scope.define(ast.name(), assignment);
        return assignment;
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Def ast) throws EvaluateException {
        if (scope.get(ast.name()).isPresent())
            throw new EvaluateException("Function is already declared in scope", ast);
        // Duplicate Parameter Check
        List<String> dupes = new ArrayList<>();
        for (var par : ast.parameters()){
            if (dupes.contains(par))
                throw new EvaluateException("There cannot be duplicate parameter names in function defintion", ast);
            dupes.add(par);
        }
        var startScope = scope;
        try {
            var functionScope = new Scope(scope);
            var function = new RuntimeValue.Function(ast.name(), args -> {
                scope = new Scope(functionScope);
                if (ast.parameters().size() != args.size())
                    throw new EvaluateException("Parameters and arguments have different number of values", ast);
                for (int i = 0; i < args.size(); i++) {
                    scope.define(ast.parameters().get(i), args.get(i));
                }
                //scope = new Scope(functionScope);
                try {
                    for (var stmt : ast.body()) {
                        visit(stmt);
                    }
                } catch (EvaluateReturn e) {
                    return e.value;
                }
                finally {
                    scope = startScope;
                }
                return new RuntimeValue.Primitive(null);
            });
            scope = startScope;
            scope.define(function.name(), function);
            return function;
        }
        finally {
            scope = startScope;
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.If ast) throws EvaluateException {
        var bool = visit(ast.condition());
        var result = requireType(bool, Boolean.class).orElseThrow(()->
                new EvaluateException("Condition does not result in a boolean value", ast.condition()));
        var prevScope = getScope();
        scope = new Scope(scope);
        try {
            if (result) {
                var thenList = ast.thenBody();
                var value = new RuntimeValue.Primitive(null);
                for (var stmt : thenList) {
                    value = (RuntimeValue.Primitive) visit(stmt);
                }
                return value;
            } else {
                var elseList = ast.elseBody();
                var value = new RuntimeValue.Primitive(null);
                for (var stmt : elseList) {
                    value = (RuntimeValue.Primitive) visit(stmt);
                }
                return value;
            }
        }
        finally {
            scope = prevScope;
        }
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.For ast) throws EvaluateException {
        var expression = requireType(visit(ast.expression()), Iterable.class).orElseThrow(
                () -> new EvaluateException("For loop expression is not iterable"));
        var startScope = scope;
        try {
            for (var item : expression) {
                var itemScope = new Scope(scope);
                itemScope.define(ast.name(), (RuntimeValue) item);
                var bodyScope = new Scope(itemScope);
                // Needed to evaluate body contents
                scope = bodyScope;
                for (var stmt : ast.body())
                    visit(stmt);
            }
        }
        finally {
            scope = startScope;
        }
        return new RuntimeValue.Primitive(null);
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Return ast) throws EvaluateException {
        if (ast.value().isEmpty())
            throw new EvaluateReturn(new RuntimeValue.Primitive(null), ast);
        var value = visit(ast.value().get());
        throw new EvaluateReturn(value, ast);
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Expression ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Stmt.Assignment ast) throws EvaluateException {
        if (ast.expression() instanceof Ast.Expr.Variable){
            // Errors if not defined
            var name = ((Ast.Expr.Variable) ast.expression()).name();
            if (scope.resolve(name).isEmpty())
                throw new EvaluateException("Variable is not defined, so it cannot be assigned");
            var value = visit(ast.value());
            scope.assign(name, value);
            return value;
        }
        else if (ast.expression() instanceof Ast.Expr.Property){
            var astProperty = (Ast.Expr.Property)ast.expression();
            var variable = visit(astProperty.receiver());
            var object = requireType(variable, RuntimeValue.ObjectValue.class).orElseThrow(
                    () -> new EvaluateException("Receiver of property is not an object"));
            // Checks if property exists in current object or prototypes
            if (object.scope().resolve(astProperty.name()).isPresent()){
                var value = visit(ast.value());
                object.scope().assign(astProperty.name(), value);
                return value;
            }
            else if (isPresentInPrototype(object, astProperty.name()).isPresent()){
                var value = visit(ast.value());
                object.scope().define(astProperty.name(), value);
                return value;
            }
            else {
                throw new EvaluateException("Property not defined, so value cannot be assigned", ast);
            }
        }
        else
            throw new EvaluateException("Receiver of assignment is invalid", ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Literal ast) throws EvaluateException {
        return new RuntimeValue.Primitive(ast.value());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Group ast) throws EvaluateException {
        return visit(ast.expression());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Binary ast) throws EvaluateException {
        switch (ast.operator()){
            case "+" -> {
                var left = visit(ast.left());
                var right = visit(ast.right());
                if (left instanceof RuntimeValue.Primitive(String leftString)){
                    return new RuntimeValue.Primitive(leftString + right.print());
                }
                else if (right instanceof RuntimeValue.Primitive(String rightString)) {
                    return new RuntimeValue.Primitive(left.print() + rightString);
                }
                else if (left instanceof RuntimeValue.Primitive(BigInteger leftInt)){
                    var rightInt = requireType(right, BigInteger.class).orElseThrow(
                            () -> new EvaluateException("Right side of op+ is not BigInteger", ast.right()));
                    return new RuntimeValue.Primitive(leftInt.add(rightInt));
                }
                else if (left instanceof RuntimeValue.Primitive(BigDecimal leftDecimal)){
                    var rightDecimal = requireType(right, BigDecimal.class).orElseThrow(
                            () -> new EvaluateException("Right side of op+ is not BigDecimal", ast.right()));
                    return new RuntimeValue.Primitive(leftDecimal.add(rightDecimal));
                }
                else
                    throw new EvaluateException("Left side of op+ is invalid", ast.left());
            }
            case "*" -> {
                var left = visit(ast.left());
                if (left instanceof RuntimeValue.Primitive(BigInteger leftInt)) {
                    var right = visit(ast.right());
                    var rightInt = requireType(right, BigInteger.class).orElseThrow(
                            () -> new EvaluateException("Right side of op* is not BigInteger", ast.right()));
                    return new RuntimeValue.Primitive(leftInt.multiply(rightInt));
                } else if (left instanceof RuntimeValue.Primitive(BigDecimal leftDecimal)) {
                    var right = visit(ast.right());
                    var rightDecimal = requireType(right, BigDecimal.class).orElseThrow(
                            () -> new EvaluateException("Right side of op* is not BigDecimal", ast.right()));
                    return new RuntimeValue.Primitive(leftDecimal.multiply(rightDecimal));
                } else
                    throw new EvaluateException("Left side of op* is invalid", ast.left());
            }
            case "-" -> {
                var left = visit(ast.left());
                if (left instanceof RuntimeValue.Primitive(BigInteger leftInt)){
                    var right = visit(ast.right());
                    var rightInt = requireType(right, BigInteger.class).orElseThrow(
                            () -> new EvaluateException("Right side of op- is not BigInteger", ast.right()));
                    return new RuntimeValue.Primitive(leftInt.subtract(rightInt));
                }
                else if (left instanceof RuntimeValue.Primitive(BigDecimal leftDecimal)){
                    var right = visit(ast.right());
                    var rightDecimal = requireType(right, BigDecimal.class).orElseThrow(
                            () -> new EvaluateException("Right side of op- is not BigDecimal", ast.right()));
                    return new RuntimeValue.Primitive(leftDecimal.subtract(rightDecimal));
                }
                else
                    throw new EvaluateException("Left side of op* is invalid", ast.left());
            }
            case "/" -> {
                var left = visit(ast.left());
                if (left instanceof RuntimeValue.Primitive(BigInteger leftInt)){
                    var right = visit(ast.right());
                    var rightInt = requireType(right, BigInteger.class).orElseThrow(
                            () -> new EvaluateException("Right side of op/ is not BigInteger", ast.right()));
                    if (rightInt.equals(BigInteger.ZERO))
                        throw new EvaluateException("Cannot divide by 0", ast.right());
                    return new RuntimeValue.Primitive(leftInt.divide(rightInt));
                }
                else if (left instanceof RuntimeValue.Primitive(BigDecimal leftDecimal)){
                    var right = visit(ast.right());
                    var rightDecimal = requireType(right, BigDecimal.class).orElseThrow(
                            () -> new EvaluateException("Right side of op/ is not BigDecimal", ast.right()));
                    if (rightDecimal.compareTo(BigDecimal.ZERO) == 0)
                        throw new EvaluateException("Cannot divide by 0", ast.right());
                    return new RuntimeValue.Primitive(leftDecimal.divide(rightDecimal, RoundingMode.HALF_EVEN));
                }
                else
                    throw new EvaluateException("Left side of op/ is invalid", ast.left());
            }
            case "==" -> {
                var left = visit(ast.left());
                var right = visit(ast.right());
                if (left.equals(right))
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            case "!=" -> {
                var left = visit(ast.left());
                var right = visit(ast.right());
                if (left.equals(right))
                    return new RuntimeValue.Primitive(false);
                else
                    return new RuntimeValue.Primitive(true);
            }
            case "<" ->{
                var result = helperComparable(ast);
                if (result.equals("<"))
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            case "<=" -> {
                var result = helperComparable(ast);
                if (result.equals("<") || result.equals("=="))
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            case ">" -> {
                var result = helperComparable(ast);
                if (result.equals(">"))
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            case ">=" -> {
                var result = helperComparable(ast);
                if (result.equals(">") || result.equals("=="))
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            case "AND" -> {
                var left = visit(ast.left());
                var leftBool = requireType(left, Boolean.class).orElseThrow(
                        () -> new EvaluateException("left side of AND is not Boolean"));
                if (!leftBool)
                    return new RuntimeValue.Primitive(false);
                var right = visit(ast.right());
                var rightBool = requireType(right, Boolean.class).orElseThrow(
                        () -> new EvaluateException("right side of AND is not Boolean"));
                // leftBool will always be true at this point, or it would have short-circuited
                if (rightBool)
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            case "OR" -> {
                var left = visit(ast.left());
                var leftBool = requireType(left, Boolean.class).orElseThrow(
                        () -> new EvaluateException("left side of OR is not Boolean"));
                if (leftBool)
                    return new RuntimeValue.Primitive(true);
                var right = visit(ast.right());
                var rightBool = requireType(right, Boolean.class).orElseThrow(
                        () -> new EvaluateException("right side of OR is not Boolean"));
                // leftBool will always be false at this point, or it would have short-circuited
                if (rightBool)
                    return new RuntimeValue.Primitive(true);
                else
                    return new RuntimeValue.Primitive(false);
            }
            default -> {
                throw new EvaluateException("Invalid Operator", ast);
            }
        }
    }

    private String helperComparable(Ast.Expr.Binary ast) throws EvaluateException {
        var left = visit(ast.left());
        var leftComp = requireType(left, Comparable.class).orElseThrow(
                () -> new EvaluateException("Left expression is not comparable", ast.left()));
        var right = visit(ast.right());
        var rightComp = requireType(right, Comparable.class).orElseThrow(
                () -> new EvaluateException("Right expression is not comparable", ast.left()));

        if (left instanceof RuntimeValue.Primitive){
            if (!(right instanceof RuntimeValue.Primitive))
                throw new EvaluateException("Right operand doesn't match the left's type", ast);
            var right_set = requireType(right, ((RuntimeValue.Primitive) left).value().getClass()).orElseThrow(
                    () -> new EvaluateException("Right operand doesn't match the left's type", ast));
        }
        if (!left.getClass().equals(right.getClass()))
            throw new EvaluateException("Right operand doesn't match the left's type:", ast);
        var result = leftComp.compareTo(rightComp);
        if (result > 0)
            return ">";
        else if (result < 0)
            return "<";
        else if (result == 0)
            return "==";
        // If the above logic fails...
        throw new EvaluateException("How did we get here in the comparable helper?", ast);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Variable ast) throws EvaluateException {
        var value = scope.resolve(ast.name()).orElseThrow(
                () -> new EvaluateException("Variable not defined in scope", ast));
        //var variable = requireType(value, RuntimeValue.Primitive.class).orElseThrow(
                //() -> new EvaluateException("Expression in scope, but not defined as a variable", ast));
        return value;
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Property ast) throws EvaluateException {
        var variable = visit(ast.receiver());
        var object = requireType(variable, RuntimeValue.ObjectValue.class).orElseThrow(
                () -> new EvaluateException("Receiver of property is not an object"));
        if (object.scope().resolve(ast.name()).isPresent())
            return object.scope().resolve(ast.name()).get();
        else if (isPresentInPrototype(object, ast.name()).isPresent()){
            return isPresentInPrototype(object, ast.name()).get();
        }
        throw new EvaluateException("Property not found in object or prototype", ast.receiver());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Function ast) throws EvaluateException {
        var value = scope.resolve(ast.name()).orElseThrow(
                () -> new EvaluateException("Function not defined in scope", ast));
        var func = requireType(value, RuntimeValue.Function.class).orElseThrow(
                () -> new EvaluateException("Expression in scope, but not defined as a function", ast));
        var arguments = new ArrayList<RuntimeValue>();
        for (Ast.Expr arg : ast.arguments())
            arguments.add(visit(arg));
        return func.definition().invoke(arguments);
    }

    @Override
    public RuntimeValue visit(Ast.Expr.Method ast) throws EvaluateException {
        var variable = visit(ast.receiver());
        var object = requireType(variable, RuntimeValue.ObjectValue.class).orElseThrow(
                () -> new EvaluateException("Receiver of method is not an object", ast.receiver()));
        if (object.scope().resolve(ast.name()).isPresent()) {
            var method = object.scope().resolve(ast.name()).get();
            var function = requireType(method, RuntimeValue.Function.class).orElseThrow(
                    () -> new EvaluateException("Found in scope, but is not a method", ast));
            var arguments = new ArrayList<RuntimeValue>();
            arguments.add(object);
            for (Ast.Expr arg : ast.arguments()) {
                var argEval = visit(arg);
                arguments.add(argEval);
            }
            var functionValue = function.definition().invoke(arguments);
            return functionValue;
        }
        else if (isPresentInPrototype(object, ast.name()).isPresent()){
                var method = isPresentInPrototype(object, ast.name()).get();
                var function = requireType(method, RuntimeValue.Function.class).orElseThrow(
                        () -> new EvaluateException("Found in prototype scope, but is not a method", ast));
                var arguments = new ArrayList<RuntimeValue>();
                arguments.add(object);
                for (Ast.Expr arg : ast.arguments()) {
                    var argEval = visit(arg);
                    arguments.add(argEval);
                }
                var functionValue = function.definition().invoke(arguments);
                return functionValue;
        }
        throw new EvaluateException("Method not found in object or prototype", ast.receiver());
    }

    @Override
    public RuntimeValue visit(Ast.Expr.ObjectExpr ast) throws EvaluateException {
        var name = ast.name();
        var prevScope = scope;
        var objectScope = new Scope(null);

        for (var let : ast.fields()){
            if (objectScope.get(let.name()).isPresent())
                throw new EvaluateException("Variable is already declared in object's scope", ast);
            var value = let.value();
            RuntimeValue assignment = new RuntimeValue.Primitive(null);
            if (value.isPresent())
                assignment = visit(value.get());
            objectScope.define(let.name(), assignment);
        }

        for (var def : ast.methods()){
            if (objectScope.get(def.name()).isPresent())
                throw new EvaluateException("Function is already declared in object's scope", ast);
            // Checks if all parameters are different names
            List<String> par_checker = new ArrayList<>();
            par_checker.add("this");
            for (var par : def.parameters()){
                if (par_checker.contains(par))
                    throw new EvaluateException("Object method cannot have a duplicate parameter name", def);
                par_checker.add(par);
            }

            // Builds the executable function
            var function = new RuntimeValue.Function(def.name(), args -> {
                var functionScope = new Scope(scope);
                scope = functionScope;

                if (def.parameters().size() + 1 != args.size())
                    throw new EvaluateException("Parameters and arguments have different number of values", ast);
                // Has to be the "method reciever", so the object?
                for (int i = 0; i < args.size(); i++){
                    if (i == 0)
                        scope.define("this", args.get(i));
                    else {
                        if (scope.get(def.parameters().get(i - 1)).isPresent())
                            throw new EvaluateException("Functions in object cannot have duplicate parameter names", ast);
                        scope.define(def.parameters().get(i - 1), args.get(i));
                    }
                }
                scope = new Scope(functionScope);
                try {
                    for (var stmt : def.body()) {
                        visit(stmt);
                    }
                }
                catch (EvaluateReturn e){
                    return e.value;
                }
                finally {
                    scope = prevScope;
                }
                return new RuntimeValue.Primitive(null);
            });
            objectScope.define(function.name(), function);
        }

        scope = prevScope;
        var object = new RuntimeValue.ObjectValue(name, objectScope);
        return object;
    }

    public Optional<RuntimeValue> isPresentInPrototype(RuntimeValue.ObjectValue object, String name){
        if (object.scope().get("prototype").isEmpty())
            return Optional.empty();
        RuntimeValue prototype = object.scope().get("prototype").get();
        if (!(prototype instanceof RuntimeValue.ObjectValue))
            return Optional.empty();
        if ((((RuntimeValue.ObjectValue) prototype).scope().resolve(name).isPresent()))
            return Optional.of(((RuntimeValue.ObjectValue) prototype).scope().resolve(name).get());
        else
            return isPresentInPrototype((RuntimeValue.ObjectValue)prototype, name);
    }

    /**
     * Helper function for extracting RuntimeValues of specific types. If type
     * is a subclass of {@link RuntimeValue} the check applies to the value
     * itself, otherwise the value must be a {@link RuntimeValue.Primitive} and
     * the check applies to the primitive value.
     */
    private static <T> Optional<T> requireType(RuntimeValue value, Class<T> type) {
        //To be discussed in lecture
        Optional<Object> unwrapped = RuntimeValue.class.isAssignableFrom(type)
            ? Optional.of(value)
            : requireType(value, RuntimeValue.Primitive.class).map(RuntimeValue.Primitive::value);
        return (Optional<T>) unwrapped.filter(type::isInstance); //cast checked by isInstance
    }

    public static class Environment {

        public static RuntimeValue sqrt(List<RuntimeValue> arguments) throws EvaluateException {
            // Assuming only one argument
            if (arguments.size() != 1)
                throw new EvaluateException("Their needs to be only one argument in the sqrt function");
            var arg = ((RuntimeValue.Primitive) arguments.get(0)).value();
            if (arg instanceof BigInteger) {
                var result = ((BigInteger) arg).sqrt();
                return new RuntimeValue.Primitive(result);
            } else if (arg instanceof BigDecimal) {
                var result = ((BigDecimal) arg).sqrt(new MathContext(16, RoundingMode.HALF_EVEN));
                return new RuntimeValue.Primitive(result);
            } else
                throw new EvaluateException("The argument attempted to be used in sqrt is not a BigInteger or BigDecimal");
        }

        public static RuntimeValue range(List<RuntimeValue> arguments) throws EvaluateException {
            if (arguments.size() != 2)
                throw new EvaluateException("Their needs to be only two argument in the range function");
            var min  = ((RuntimeValue.Primitive)arguments.get(0));
            var start = requireType(min, BigInteger.class).orElseThrow(
                    () -> new EvaluateException("The start argument is not a BigInteger"));
            var max = ((RuntimeValue.Primitive)arguments.get(1));
            var end = requireType(max, BigInteger.class).orElseThrow(
                    () -> new EvaluateException("The end argument is not a BigInteger"));
            if (start.compareTo(end) > 0)
                throw new EvaluateException("The Start value is greater than the End value");
            var list = new ArrayList<>();
            for (var i = start; i.compareTo(end) < 0; i = i.add(BigInteger.ONE)){
                list.add(new RuntimeValue.Primitive(i));
            }
            return new RuntimeValue.Primitive(list);
        }
    }
    public final class EvaluateReturn extends RuntimeException {
        RuntimeValue value;
        Ast.Stmt.Return stmt;

        EvaluateReturn(RuntimeValue value, Ast.Stmt.Return stmt ){
            this.value = value;
            this.stmt = stmt;
        }
    }
}

