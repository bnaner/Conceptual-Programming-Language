package plc.project.analyzer;

import plc.project.parser.Ast;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

public final class Analyzer implements Ast.Visitor<Type, AnalyzeException> {

    private Context context;

    // Function and Objects are special cases, make note of this
    private final List<Type> valid_types = List.of(Type.ANY, Type.DYNAMIC, Type.NIL, Type.BOOLEAN, Type.INTEGER,
            Type.DECIMAL, Type.CHARACTER, Type.STRING, Type.EQUATABLE, Type.COMPARABLE, Type.ITERABLE);

    public Analyzer(Scope scope) {
        this.context = new Context(scope, Optional.empty(), new HashSet<>(), false);
    }

    public Context getContext() {
        return context;
    }



    @Override
    public Type visit(Ast.Source ast) throws AnalyzeException {
        Type type = Type.NIL;
        for (var statement : ast.statements()) {
            type = visit(statement);
        }
        return type;
    }

    @Override
    public Type visit(Ast.Stmt.Let ast) throws AnalyzeException {
        if (context.scope().get(ast.name()).isPresent())
            throw new AnalyzeException("Variable has already been declared.", ast);
        Type type = null;
        if (ast.type().isPresent()){
            var declared_type = new Type.Primitive(ast.type().get());
            if (!valid_types.contains(declared_type))
                throw new AnalyzeException("The declared type is invalid", ast);
            type = declared_type;
        }
        if (ast.value().isPresent()){
            var value = visit(ast.value().get());
            if (type == null)
                type = value;
            else{
                if (!value.isSubtypeOf(type))
                    throw new AnalyzeException("Value of new variable is not a subtype of it", ast);
            }
            context.uninitialized().remove(ast.name());
        }
        else
            context.uninitialized().add(ast.name());

        if (type == null)
            type = Type.DYNAMIC;
        context.scope().declare(ast.name(), type);
        return type;
    }

    @Override
    public Type visit(Ast.Stmt.Def ast) throws AnalyzeException {
        if (context.scope().get(ast.name()).isPresent())
            throw new AnalyzeException("Function has already been defined in this scope", ast);
        var curr_context = context;
        // Reverts context if error occurs
        try {
            // Saves all parameter types in a new scope if listed out
            var def_context = new Context(curr_context);
            context = def_context;
            List<Type> parameters = new ArrayList<>();
            for (var par : ast.parameterTypes()) {
                if (par.isPresent()) {
                    // This assumes functions and objects can't be passed as parameters
                    var parType = new Type.Primitive(par.get());
                    if (!valid_types.contains(parType))
                        throw new AnalyzeException("Parameter is set to an invalid type", ast);
                    parameters.add(parType);
                }
                else
                    parameters.add(Type.DYNAMIC);
            }
            // Saves the remainder of types that may not be listed out
            while (parameters.size() < ast.parameters().size())
                parameters.add(Type.DYNAMIC);
            // Saves the functions return type
            var return_type = Type.DYNAMIC;
            if (ast.returnType().isPresent()) {
                if (!valid_types.contains(new Type.Primitive(ast.returnType().get())))
                    throw new AnalyzeException("Defined function return type is invalid");
                return_type = new Type.Primitive(ast.returnType().get());
            }
            // Creates function and body context for shadowing purposes
            var func = new Type.Function(parameters, return_type);
            var func_context = new Context(def_context.scope(), Optional.of(func), def_context.uninitialized(), def_context.returns());
            // Adds function and parameters to scope so that they can be accessed
            func_context.scope().declare(ast.name(), func);
            for (int i = 0; i < parameters.size(); i++) {
                // Catches Errors if duplicates are found
                if (func_context.scope().get(ast.parameters().get(i)).isPresent())
                    throw new AnalyzeException("Parameters in defined function cannot have the same name", ast);
                func_context.scope().declare(ast.parameters().get(i), parameters.get(i));
            }

            context = func_context;
            // Analyzes the body of the function definition
            for (var stmt : ast.body()) {
                if (func_context.returns())
                    throw new AnalyzeException("Unreachable statement after function returns", ast);
                visit(stmt);
            }
            // If a return type is defined, and no return statement is there, this catches that (Dynamic is default if no return is expected)
            if (!func_context.returns() && !(return_type.equals(Type.DYNAMIC) || return_type.equals(Type.NIL)))
                throw new AnalyzeException("The defined function does not properly return a desired type", ast);
            context = curr_context;
            context.scope().declare(ast.name(), func);
            return func;
        }
        finally {
            context = curr_context;
        }
    }

    @Override
    public Type visit(Ast.Stmt.If ast) throws AnalyzeException {
        var condition = visit(ast.condition());
        if (!condition.equals(Type.BOOLEAN))
            throw new AnalyzeException("Condition of if statement is not of type Boolean", ast.condition());

        // Current Context
        var curr_context = context;

        // Maintains scope in case of errors
        try {
            // If Block
            var if_context = new Context(curr_context);
            context = if_context;
            for (var stmt : ast.thenBody()) {
                if (context.returns())
                    throw new AnalyzeException("Unreachable statement in IF block", ast);
                visit(stmt);
            }
            if_context = context;

            // Else Block
            var else_context = new Context(curr_context);
            context = else_context;
            for (var stmt : ast.elseBody()) {
                if (context.returns())
                    throw new AnalyzeException("Unreachable statement in ELSE block", ast);
                visit(stmt);
            }
            else_context = context;

            context = curr_context;
            curr_context.merge(List.of(if_context, else_context));
            return Type.DYNAMIC;
        }
        finally {
            context = curr_context;
        }
    }

    @Override
    public Type visit(Ast.Stmt.For ast) throws AnalyzeException {
        var expr = visit(ast.expression());
        if (!expr.isSubtypeOf(Type.ITERABLE))
            throw new AnalyzeException("For loop expression must be Iterable! (Integer)", ast.expression());
        var curr_context = context;
        var for_context = new Context(curr_context);

        context = for_context;
        // According to spec, value of variable in FOR will always be integer
        try {
            context.scope().declare(ast.name(), Type.INTEGER);
            for (var stmt : ast.body()) {
                if (context.returns())
                    throw new AnalyzeException("Unreachable statement in FOR loop", ast);
                visit(stmt);
            }
            return Type.NIL;
        }
        finally {
            context = curr_context;
            context.returns(for_context.returns());
        }
    }

    @Override
    public Type visit(Ast.Stmt.Return ast) throws AnalyzeException {
        // Check if called out of function
        if (context.function().isEmpty())
            throw new AnalyzeException("Return statement is outside of a function", ast);
        Type return_value = Type.NIL;
        if (ast.value().isPresent())
            return_value = visit(ast.value().get());
        if (!return_value.isSubtypeOf(context.function().get().returns()))
            throw new AnalyzeException("Return value is not a subtype of the function's return value", ast);
        context.returns(true);
        //throw new AnalyzeReturn(return_value, ast);
        return return_value;
    }

    @Override
    public Type visit(Ast.Stmt.Expression ast) throws AnalyzeException {
        return visit(ast.expression());
    }

    @Override
    public Type visit(Ast.Stmt.Assignment ast) throws AnalyzeException {
        if (ast.expression() instanceof Ast.Expr.Variable(String name)){
            if (context.scope().resolve(name).isEmpty())
                throw new AnalyzeException("Variable undefined, so it cannot be assigned a value", ast.expression());
            // Visit is not being used on variable, as that function needs variable to be defined
            var variable = context.scope().resolve(name).get();
            var value = visit(ast.value());
            if (!value.isSubtypeOf(variable))
                throw new AnalyzeException("Value of assignment is not a subtype of the variable assigned", ast.value());
            context.uninitialized().remove(name);
            // Should it return the type of the variable or value assigned? (assuming value based of evaluator)
            return value;
        }
        else if (ast.expression() instanceof Ast.Expr.Property) {
            Ast.Expr.Property astProp = (Ast.Expr.Property) ast.expression();
            Type receiver = visit((astProp.receiver()));
            var value = visit(ast.value());
            if (receiver.equals(Type.DYNAMIC))
                return value;
            if (!(receiver instanceof Type.ObjectType))
                throw new AnalyzeException("Receiver of property is not an object", ast.expression());
            if (((Type.ObjectType) receiver).scope().resolve((astProp.name())).isPresent()){
                Type previous = ((Type.ObjectType) receiver).scope().resolve((astProp.name())).get();
                if (!value.isSubtypeOf(previous))
                    throw new AnalyzeException("Value assigned is not a subtype of the properties value", ast);
                // Since its already in scope, I guess we just pass the type of newly assigned value?
                return value;
            }
            else if (isPresentInPrototype((Type.ObjectType)receiver, astProp.name()).isPresent()){
                Type previous = isPresentInPrototype((Type.ObjectType)receiver, astProp.name()).get();
                if (!value.isSubtypeOf(previous))
                    throw new AnalyzeException("Value assigned is not a subtype of the properties value", ast);
                ((Type.ObjectType) receiver).scope().declare(astProp.name(), value);
                return value;
            }
            else
                throw new AnalyzeException("Property not found in object or prototypes", ast.expression());
        }
        else
            throw new AnalyzeException("Receiver of assignment is not a variable or property", ast.expression());
    }

    @Override
    public Type visit(Ast.Expr.Literal ast) throws AnalyzeException {
        return switch (ast.value()) {
            case null -> Type.NIL;
            case Boolean _ -> Type.BOOLEAN;
            case BigInteger _ -> Type.INTEGER;
            case BigDecimal _ -> Type.DECIMAL;
            case Character _ -> Type.CHARACTER;
            case String _ -> Type.STRING;
            default -> throw new AssertionError(ast.value().getClass());
        };
    }

    @Override
    public Type visit(Ast.Expr.Group ast) throws AnalyzeException {
        return visit(ast.expression());
    }

    @Override
    public Type visit(Ast.Expr.Binary ast) throws AnalyzeException {
        switch (ast.operator()){
            case "+", "-", "*", "/":{
                return arithmeticBinary(ast, ast.operator());
            }
            case "==", "!=":{
                Type leftType = visit(ast.left());
                Type rightType = visit(ast.right());
                if (leftType.isSubtypeOf(rightType) || rightType.isSubtypeOf(leftType))
                    return Type.BOOLEAN;
                else
                    // Since this "errors" on the right operand, should it be ast.right? But ast gives better info
                    throw new AnalyzeException("Neither the left or right operand are a subtype of another.", ast);
            }
            case "<", "<=", ">", ">=":{
                Type leftType = visit(ast.left());
                if (!leftType.isSubtypeOf(Type.COMPARABLE))
                    throw new AnalyzeException("Left operand is not of type comparable.", ast.left());
                Type rightType = visit(ast.right());
                if (!rightType.isSubtypeOf(leftType))
                    throw new AnalyzeException("Right operand is not a subtype of the left operand", ast.right());
                return Type.BOOLEAN;
            }
            case "AND", "OR":{
                Type leftType = visit(ast.left());
                if (!leftType.isSubtypeOf(Type.BOOLEAN))
                    throw new AnalyzeException("Left operand must be a boolean.", ast.left());
                Type rightType = visit(ast.right());
                if (!rightType.isSubtypeOf(Type.BOOLEAN))
                    throw new AnalyzeException("Right operand must be a boolean.", ast.right());
                return Type.BOOLEAN;
            }
            default:{
                throw new AnalyzeException("Operator is invalid for binary expression.", ast);
            }
        }
    }

    private Type arithmeticBinary(Ast.Expr.Binary ast, String op) throws AnalyzeException {
        Type leftType = visit(ast.left());
        if (op.equals("+") && (leftType.equals(Type.STRING) || visit(ast.right()).equals(Type.STRING)))
            return Type.STRING;
        else if (leftType.equals(Type.INTEGER) || leftType.equals(Type.DECIMAL)){
            var rightType = visit(ast.right());
            if (!rightType.isSubtypeOf(leftType))
                throw new AnalyzeException("Right operand is not a subtype of left operand.", ast.right());
            return leftType;
        }
        else if (leftType.equals(Type.DYNAMIC)){
            var rightType = visit(ast.right());
            // Can right be any other type? (first 'if' catches string, dynamic caught by isSubtype)
            if (rightType.isSubtypeOf(Type.INTEGER) || rightType.isSubtypeOf(Type.DECIMAL))
                return rightType;
            else
                throw new AnalyzeException("Right operand is not a valid type (left is dynamic).", ast.right());
        }
        else
            throw new AnalyzeException("Left operand type is not a valid type.", ast.left());
    }

    @Override
    public Type visit(Ast.Expr.Variable ast) throws AnalyzeException {
        var type = context.scope().resolve(ast.name()).orElseThrow(
            () -> new AnalyzeException("Variable is undefined.", ast));
        if (context.uninitialized().contains(ast.name()))
            throw new AnalyzeException("Variable is uninitialized", ast);
        return type;
    }

    @Override
    public Type visit(Ast.Expr.Property ast) throws AnalyzeException {
        // Presumably an error is thrown if not found in scope
        var receiver = visit(ast.receiver());
        // We assume that if the reciever/prototype is dynamic, we just assume it has the property and is dynamic
        if (receiver.equals(Type.DYNAMIC))
            return Type.DYNAMIC;
        else if (!(receiver instanceof Type.ObjectType))
            throw new AnalyzeException("Receiver of property is not an object.", ast.receiver());

        if (((Type.ObjectType) receiver).scope().resolve(ast.name()).isEmpty()){
            var type = isPresentInPrototype((Type.ObjectType) receiver, ast.name());
            if (type.isEmpty())
                throw new AnalyzeException("Property cannot be found in object or prototype", ast);
            else
                return type.get();
            /* Remove if no issues with property
            var prototype = ((Type.ObjectType) receiver).scope().resolve("prototype");
            if (prototype.isPresent()){
                var obj_prototype = prototype.get();
                if (obj_prototype.equals(Type.DYNAMIC))
                    return Type.DYNAMIC;
                else if (!(obj_prototype instanceof Type.ObjectType))
                    throw new AnalyzeException("Property not found in object, and prototype is not a object", ast.receiver());
                else if (((Type.ObjectType) obj_prototype).scope().resolve(ast.name()).isPresent()){
                    var prop = ((Type.ObjectType) obj_prototype).scope().resolve(ast.name()).get();
                    return prop;
                }
                else
                    throw new AnalyzeException("Property cannot be found in object or prototype", ast);
            }
            else
                throw new AnalyzeException("Property cannot be found in object.", ast);*/
        }
        else{
            // Property found in Object
            var prop = ((Type.ObjectType) receiver).scope().resolve(ast.name()).get();
            return prop;
        }
    }

    @Override
    public Type visit(Ast.Expr.Function ast) throws AnalyzeException {
        var func_check = context.scope().resolve(ast.name());
        if (func_check.isEmpty())
            throw new AnalyzeException("Function undefined.", ast);
        else if (!(func_check.get() instanceof Type.Function))
            throw new AnalyzeException("Object called is not a function", ast);

        var func = (Type.Function) func_check.get();
        if (ast.arguments().size() != func.parameters().size())
            throw new AnalyzeException("Function parameters and arguments are of different arity.", ast);
        for (int i = 0; i < ast.arguments().size(); i++){
            if (!visit(ast.arguments().get(i)).isSubtypeOf(func.parameters().get(i)))
                throw new AnalyzeException("Function parameters and arguments are of different types", ast);
        }
        return func.returns();
    }

    @Override
    public Type visit(Ast.Expr.Method ast) throws AnalyzeException {
        // Copied from property function, slightly tweaked
        // Presumably an error is thrown if not found in scope
        var receiver = visit(ast.receiver());
        // We assume that if the reciever/prototype is dynamic, we just assume it has the property and is dynamic
        if (receiver.equals(Type.DYNAMIC))
            return Type.DYNAMIC;
        else if (!(receiver instanceof Type.ObjectType))
            throw new AnalyzeException("Receiver of method is not an object.", ast.receiver());

        if (((Type.ObjectType) receiver).scope().resolve(ast.name()).isEmpty()){
            var prototype = ((Type.ObjectType) receiver).scope().resolve("prototype");
            if (prototype.isPresent()){
                var obj_prototype = prototype.get();
                if (obj_prototype.equals(Type.DYNAMIC))
                    return Type.DYNAMIC;
                else if (!(obj_prototype instanceof Type.ObjectType))
                    throw new AnalyzeException("Method not found in object, and prototype is not a object", ast.receiver());
                else if (((Type.ObjectType) obj_prototype).scope().resolve(ast.name()).isPresent()){
                    // Method found in prototype
                    //TODO: Use the new recursive prototype function for method
                    var func_check = ((Type.ObjectType) obj_prototype).scope().resolve(ast.name()).get();
                    if (!(func_check instanceof Type.Function))
                        throw new AnalyzeException("Variable accessed in receiver prototype is not a method", ast);
                    var func = (Type.Function)func_check;
                    if (func.parameters().size() != ast.arguments().size())
                        throw new AnalyzeException("Prototype method parameters and arguments are not of the same arity.", ast);
                    for (int i = 0; i < func.parameters().size(); i++){
                        if (!visit(ast.arguments().get(i)).isSubtypeOf(func.parameters().get(i)))
                            throw new AnalyzeException("Prototype method argument is not subtype of parameter", ast);
                    }
                    return func.returns();
                }
                else
                    throw new AnalyzeException("Method cannot be found in object or prototype", ast);
            }
            else
                throw new AnalyzeException("Method cannot be found in object.", ast);
        }
        else{
            // Method found in Object
            var func_check = ((Type.ObjectType) receiver).scope().resolve(ast.name()).get();
            if (!(func_check instanceof Type.Function))
                throw new AnalyzeException("Variable accessed in receiver is not a method", ast);
            var func = (Type.Function)func_check;
            if (func.parameters().size() != ast.arguments().size())
                throw new AnalyzeException("Method parameters and arguments are not of the same arity.", ast);
            for (int i = 0; i < func.parameters().size(); i++){
                if (!visit(ast.arguments().get(i)).isSubtypeOf(func.parameters().get(i)))
                    throw new AnalyzeException("Method argument is not subtype of parameter", ast);
            }
            return func.returns();
        }
    }

    @Override
    public Type visit(Ast.Expr.ObjectExpr ast) throws AnalyzeException {
        if (ast.name().isPresent() && context.scope().get(ast.name().get()).isPresent())
            throw new AnalyzeException("Object is already defined in scope", ast);
        var curr_context = context;

        // Assuming object is created with current scope, but can only has its defined ones in its own.
    var object_context = new Context(context);
        context = object_context;
        // Safety Block, ensures context reverts after
        try{
            // Copied from Let Statement
            var object_scope = new Scope(null);
            var object = new Type.ObjectType(ast.name(), object_scope);
            for (var let : ast.fields()){
                if (object_scope.get(let.name()).isPresent())
                    throw new AnalyzeException("Object Variable has already been declared.", let);
                Type type = null;
                if (let.type().isPresent()){
                    if (!valid_types.contains(new Type.Primitive(let.type().get())))
                        throw new AnalyzeException("Object field defined with invalid type", let);
                    type = new Type.Primitive(let.type().get());
                }
                if (let.value().isPresent()){
                    var value = visit(let.value().get());
                    if (type == null)
                        type = value;
                    else{
                        if (!value.isSubtypeOf(type))
                            throw new AnalyzeException("Value of new Object variable is not a subtype of it", ast);
                    }
                    // Limitation: Object doesn't require initialization?
                    //context.uninitialized().remove(let.name());
                }
                //else
                    //context.uninitialized().add(let.name());

                if (type == null)
                    type = Type.DYNAMIC;
                object_scope.declare(let.name(), type);
            }
            // Copied from DEF
            List<Context> func_context_list = new ArrayList<>();
            for (var def : ast.methods()){
                if (object_scope.get(def.name()).isPresent())
                    throw new AnalyzeException("Function has already been defined in this scope", def);

                // Saves all parameter types in a new scope if listed out
                var def_context = new Context(object_context);
                context = def_context;
                List<Type> parameters = new ArrayList<>();
                for (var par : def.parameterTypes()) {
                    if (par.isPresent()) {
                        if (!valid_types.contains(new Type.Primitive(par.get())))
                            throw new AnalyzeException("The parameter in the defined object method is invalid", def);
                        parameters.add(new Type.Primitive(par.get()));
                    }
                    else
                        parameters.add(Type.DYNAMIC);
                }
                // Saves the remainder of types that may not be listed out
                while (parameters.size() < def.parameters().size())
                    parameters.add(Type.DYNAMIC);
                // Saves the functions return type
                var return_type = Type.DYNAMIC;
                if (def.returnType().isPresent()) {
                    if (!valid_types.contains(new Type.Primitive(def.returnType().get())))
                        throw new AnalyzeException("The return type of the defined object method is invalid", def);
                    return_type = new Type.Primitive(def.returnType().get());
                }
                // Creates function and body context for shadowing purposes
                var func = new Type.Function(parameters, return_type);
                var func_context = new Context(def_context.scope(), Optional.of(func), def_context.uninitialized(), def_context.returns());
                // Adds function and parameters to scope so that they can be accessed
                func_context.scope().declare(def.name(), func);
                for (int i = 0; i < parameters.size(); i++) {
                    // Catches errors if duplicate parameter names exist
                    if (func_context.scope().get(def.parameters().get(i)).isPresent())
                        throw new AnalyzeException("Object method cannot have duplicate parameter names", def);
                    func_context.scope().declare(def.parameters().get(i), parameters.get(i));
                }
                // Adds object to scope so it can be accessed
                func_context.scope().declare("this", object);
                object_scope.declare(def.name(), func);

                func_context_list.add(func_context);
            }
            // After setting up, we can finally analyze the body of each definition (allows future function usage)
            for (int i = 0; i < ast.methods().size(); i++){
                context = func_context_list.get(i);
                for (var stmt : ast.methods().get(i).body())
                    visit(stmt);
                context = object_context;
            }
            if (ast.name().isPresent())
                context.scope().declare(ast.name().get(), object);
            return object;
        }
        finally {
            context = curr_context;
        }
    }

    public Optional<Type> isPresentInPrototype(Type.ObjectType object, String name){
        if (object.scope().get("prototype").isEmpty())
            return Optional.empty();
        Type prototype = object.scope().get("prototype").get();
        if (prototype.equals(Type.DYNAMIC))
            return Optional.of(Type.DYNAMIC);
        if (!(prototype instanceof Type.ObjectType))
            return Optional.empty();
        if ((((Type.ObjectType) prototype).scope().resolve(name).isPresent()))
            return Optional.of(((Type.ObjectType) prototype).scope().resolve(name).get());
        else
            return isPresentInPrototype((Type.ObjectType)prototype, name);
    }

    public static final class ContextHooks {

        public static Set<String> mergeUninitialized(List<Set<String>> children) {
            Set<String> merged = new HashSet<>();
            for (var uninitialized : children)
                merged.addAll(uninitialized);
            return merged;
        }

        public static boolean mergeReturns(List<Boolean> children) {
            for (var result : children){
                if (!result)
                    return false;
            }
            return true;
        }

    }

    public static final class EnvironmentHooks {

        public static final Type SQRT = new Type.Function(List.of(Type.DECIMAL), Type.DECIMAL);
        public static final Type RANGE = new Type.Function(List.of(Type.INTEGER, Type.INTEGER), Type.ITERABLE);

    }

    public static final class TypeHooks {

        public static boolean isSubtypeOf(Type subtype, Type supertype) {
            if (subtype.equals(supertype) || supertype.equals(Type.ANY))
                return true;
            else if (subtype.equals(Type.DYNAMIC) || supertype.equals(Type.DYNAMIC))
                return true;
            else if ((subtype.equals(Type.NIL) || subtype.equals(Type.COMPARABLE) || subtype.equals(Type.ITERABLE)
                    || subtype instanceof Type.ObjectType) && supertype.equals(Type.EQUATABLE))
                return true;
            else if ((subtype.equals(Type.BOOLEAN) || subtype.equals(Type.INTEGER) || subtype.equals(Type.DECIMAL)
                    || subtype.equals(Type.CHARACTER) || subtype.equals(Type.STRING)) && (supertype.equals(Type.COMPARABLE)
                    || supertype.equals(Type.EQUATABLE))) // All comparable types are equatable.
                return true;
            else if (subtype instanceof Type.ObjectType){
                if (((Type.ObjectType) subtype).scope().resolve("prototype").isPresent()){
                    Type prototype = ((Type.ObjectType) subtype).scope().resolve("prototype").get();
                    return isSubtypeOf(prototype, supertype);
                }
                else{
                    // Fix this up more later
                    if (!(supertype instanceof Type.ObjectType))
                        return false;
                    return supertype.equals(subtype);
                }
            }
            else
                return false;
        }

    }
    /* Is this necessary?
    public final class AnalyzeReturn extends RuntimeException {
        Type value;
        Ast.Stmt.Return stmt;

        AnalyzeReturn(Type value, Ast.Stmt.Return stmt ){
            this.value = value;
            this.stmt = stmt;
        }
    }
    */
}
