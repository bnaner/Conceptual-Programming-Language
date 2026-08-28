package plc.project.parser;

import com.google.common.base.Preconditions;
import plc.project.lexer.Token;

import javax.swing.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * This style of parser is called <em>recursive descent</em>. Each rule in our
 * grammar has dedicated function, and references to other rules correspond to
 * calling that function. Recursive rules are therefore supported by actual
 * recursive calls, while operator precedence is encoded via the grammar.
 *
 * <p>The parser has a similar architecture to the lexer, just with
 * {@link Token}s instead of characters. As before, {@link TokenStream#peek} and
 * {@link TokenStream#match} help with traversing the token stream. Instead of
 * emitting tokens, you will instead need to extract the literal value via
 * {@link TokenStream#get} to be added to the relevant AST.
 */
public final class   Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    public Ast parse(String rule) throws ParseException {
        var ast = switch (rule) {
            case "source" -> parseSource();
            case "stmt" -> parseStmt();
            case "expr" -> parseExpr();
            default -> throw new AssertionError(rule);
        };
        if (tokens.has(0)) {
            throw new ParseException("Expected end of input.", tokens.getNext());
        }
        return ast;
    }

    private Ast.Source parseSource() throws ParseException {
        var statements = new ArrayList<Ast.Stmt>();
        while (tokens.has(0)) {
            statements.add(parseStmt());
        }
        return new Ast.Source(statements);
    }

    private Ast.Stmt parseStmt() throws ParseException {
        if (tokens.peek("LET"))
            return parseLetStmt();
        else if (tokens.peek("DEF"))
            return parseDefStmt();
        else if (tokens.peek("IF"))
            return parseIfStmt();
        else if (tokens.peek("FOR"))
            return parseForStmt();
        else if (tokens.peek("RETURN"))
            return parseReturnStmt();
        else
            return parseExpressionOrAssignmentStmt();
    }

    private Ast.Stmt parseLetStmt() throws ParseException {
        Preconditions.checkState(tokens.match("LET"));
        if (!tokens.match(Token.Type.IDENTIFIER))
            throw new ParseException("Expected Identifier in LET statement", tokens.getNext());
        var identifier = tokens.get(-1).literal();

        // Code added for Analyzer
        Optional<String> type = Optional.empty();
        if (tokens.match(":")){
            if (!tokens.match(Token.Type.IDENTIFIER))
                throw new ParseException("Expected Identifier in Let statement typing", tokens.getNext());
            type = Optional.of(tokens.get(-1).literal());
        }

        if (tokens.match("=")){
            var expr = parseExpr();
            if (!tokens.match(";"))
                throw new ParseException("LET statement missing ;", tokens.getNext());
            return new Ast.Stmt.Let(identifier, type, Optional.of(expr));
        }
        else{
            if (!tokens.match(";"))
                throw new ParseException("LET statement missing ;", tokens.getNext());
            return new Ast.Stmt.Let(identifier, type, Optional.empty());
        }
    }

    private Ast.Stmt parseDefStmt() throws ParseException {
        Preconditions.checkState(tokens.match("DEF"));
        if (!tokens.match(Token.Type.IDENTIFIER))
            throw new ParseException("Expected Identifier in DEF statement", tokens.getNext());
        var name = tokens.get(-1).literal();
        if (!tokens.match("("))
            throw new ParseException("Expected Open Parenthesis in DEF statement", tokens.getNext());
        var parameters = new ArrayList<String>();
        var parameterTypes = new ArrayList<Optional<String>>();
        if (!tokens.match(")")){
            if (!tokens.match(Token.Type.IDENTIFIER))
                throw new ParseException("Expected Identifier in DEF statement", tokens.getNext());
            parameters.add(tokens.get(-1).literal());
            // Gets type of first parameter if it exists
            if (tokens.match(":"))
                if (tokens.match(Token.Type.IDENTIFIER))
                    parameterTypes.add(Optional.of(tokens.get(-1).literal()));
                else
                    throw new ParseException("Expected type identifier for parameter in DEF statement", tokens.getNext());
            else
                parameterTypes.add(Optional.empty());

            while (tokens.match(",", Token.Type.IDENTIFIER)){
                parameters.add(tokens.get(-1).literal());
                // Has error checking compared to above, but is it needed?
                if (tokens.match(":"))
                    if (tokens.match(Token.Type.IDENTIFIER))
                        parameterTypes.add(Optional.of(tokens.get(-1).literal()));
                    else
                        throw new ParseException("Expected type identifier for parameter in DEF statement", tokens.getNext());
                else
                    parameterTypes.add(Optional.empty());
            }
            if (!tokens.match(")"))
                throw new ParseException("Expected Closing Parenthesis in DEF statement", tokens.getNext());
        }
        // Returns type of function
        Optional<String> return_type = Optional.empty();
        if (tokens.match(":"))
            if (tokens.match(Token.Type.IDENTIFIER))
                return_type = Optional.of(tokens.get(-1).literal());
            else
                throw new ParseException("Expected identifier for function return type in DEF statement", tokens.getNext());

        if (!tokens.match("DO"))
            throw new ParseException("Expected DO in DEF statement", tokens.getNext());
        // May have an issue if END doesn't exist?
        var body = new ArrayList<Ast.Stmt>();
        while (!tokens.match("END")){
            body.add(parseStmt());
        }
        return new Ast.Stmt.Def(name, parameters, parameterTypes, return_type, body);
    }

    private Ast.Stmt parseIfStmt() throws ParseException {
        Preconditions.checkState(tokens.match("IF"));
        var expr = parseExpr();
        if (!tokens.match("DO"))
            throw new ParseException("Expected \"DO\" in IF statement", tokens.getNext());
        var thenBody = new ArrayList<Ast.Stmt>();
        var elseBody = new ArrayList<Ast.Stmt>();
        while (!tokens.peek("ELSE") && !tokens.peek("END"))
            thenBody.add(parseStmt());
        if (tokens.match("ELSE")){
            while (!tokens.peek("END"))
                elseBody.add(parseStmt());
        }
        if (!tokens.match("END"))
            throw new ParseException("Expected end of IF Statement to contain \"END\"", tokens.getNext());
        return new Ast.Stmt.If(expr, thenBody, elseBody);
    }

    private Ast.Stmt parseForStmt() throws ParseException {
        Preconditions.checkState(tokens.match("FOR"));
        if (!tokens.match(Token.Type.IDENTIFIER))
            throw new ParseException("Expected Identifier in FOR Statement", tokens.getNext());
        var name = tokens.get(-1).literal();
        if (!tokens.match("IN"))
            throw new ParseException("Expected \"IN\" After Identifier in FOR Statement", tokens.getNext());
        var expr = parseExpr();
        if (!tokens.match("DO"))
            throw new ParseException("Expected \"DO\" After Expression in FOR Statement", tokens.getNext());
        var body = new ArrayList<Ast.Stmt>();
        // May have potential issue if END doesn't exist?
        while (!tokens.match("END")) {body.add(parseStmt());}
        return new Ast.Stmt.For(name, expr, body);
    }

    private Ast.Stmt parseReturnStmt() throws ParseException {
        Preconditions.checkState(tokens.match("RETURN"));
        Optional<Ast.Expr> value = Optional.empty();
        if (!tokens.peek("IF") && !tokens.peek(";"))
            value = Optional.of(parseExpr());
        if (tokens.match("IF")){
            var cond = parseExpr();
            if (!tokens.match(";"))
                throw new ParseException("Expected ; at end of RETURN statement", tokens.getNext());
            return new Ast.Stmt.If(cond, List.of(new Ast.Stmt.Return(value)), new ArrayList<>());
        }
        if (!tokens.match(";"))
            throw new ParseException("Expected ; at end of RETURN statement", tokens.getNext());
        return new Ast.Stmt.Return(value);
    }

    private Ast.Stmt parseExpressionOrAssignmentStmt() throws ParseException {
        var expr = parseExpr();
        if (tokens.match("=")){
            var assignment = parseExpr();
            if (!tokens.match(";"))
                throw new ParseException("Assignment Statement Missing ;", tokens.getNext());
            return new Ast.Stmt.Assignment(expr, assignment);
        }
        else {
            if (!tokens.match(";"))
                throw new ParseException("Expression Statement Missing ;", tokens.getNext());
            return new Ast.Stmt.Expression(expr);
        }
    }

    private Ast.Expr parseExpr() throws ParseException {
        return parseLogicalExpr();
    }

    private Ast.Expr parseLogicalExpr() throws ParseException {
        var first = parseComparisonExpr();
        while (tokens.match("AND") || tokens.match("OR")){
            var operator = tokens.get(-1).literal();
            var second = parseComparisonExpr();
            first = new Ast.Expr.Binary(operator, first, second);
        }
        return first;
    }

    private Ast.Expr parseComparisonExpr() throws ParseException {
        var first = parseAdditiveExpr();
        while (tokens.match("<") || tokens.match("<=") || tokens.match(">")
                || tokens.match(">=") || tokens.match("==") || tokens.match("!=")){
            var operator = tokens.get(-1).literal();
            var second = parseAdditiveExpr();
            first = new Ast.Expr.Binary(operator, first, second);
        }
        return first;
    }

    private Ast.Expr parseAdditiveExpr() throws ParseException {
        var first = parseMultiplicativeExpr();
        while (tokens.match("+") || tokens.match("-")){
            var operator = tokens.get(-1).literal();
            var second = parseMultiplicativeExpr();
            first = new Ast.Expr.Binary(operator, first, second);
        }
        return first;
    }

    private Ast.Expr parseMultiplicativeExpr() throws ParseException {
        var first = parseSecondaryExpr();
        while (tokens.match("*") || tokens.match("/")){
            var operator = tokens.get(-1).literal();
            var second = parseSecondaryExpr();
            first = new Ast.Expr.Binary(operator, first, second);
        }
        return first;
    }

    private Ast.Expr parseSecondaryExpr() throws ParseException {
        var primary = parsePrimaryExpr();
        if (tokens.peek(".")){
            var access = primary;
            while (tokens.peek(".")){
                access = parsePropertyOrMethod(access);
            }
            return access;
        }
        else
            return primary;
    }

    private Ast.Expr parsePropertyOrMethod(Ast.Expr receiver) throws ParseException {
        Preconditions.checkState(tokens.match("."));
        if (!tokens.match(Token.Type.IDENTIFIER))
            throw new ParseException("Expected IDENTIFIER for Property/Method", tokens.getNext());
        var identifier = tokens.get(-1).literal();
        // Checks if property or method
        if (tokens.match("(")){
            List<Ast.Expr> arguments = new ArrayList<>();
            // Checks if method contains arguments
            if (!tokens.match(")")){
                var expr = parseExpr();
                arguments.add(expr);
                while (tokens.match(",")){
                    expr = parseExpr();
                    arguments.add(expr);
                }
                if (!tokens.match(")")){
                    throw new ParseException("Expected Closing Parenthesis", tokens.getNext());
                }
            }
            return new Ast.Expr.Method(receiver, identifier, arguments);
        }
        return new Ast.Expr.Property(receiver, identifier);
    }

    private Ast.Expr parsePrimaryExpr() throws ParseException {
        if (tokens.peek("TRUE") || tokens.peek("FALSE")|| tokens.peek(Token.Type.INTEGER)
                || tokens.peek(Token.Type.DECIMAL) || tokens.peek(Token.Type.CHARACTER)
                || tokens.peek(Token.Type.STRING) || tokens.peek("NIL")){
            return parseLiteralExpr();
        }
        else if (tokens.peek("("))
            return parseGroupExpr();
        else if (tokens.peek("OBJECT"))
            return parseObjectExpr();
        else if (tokens.peek(Token.Type.IDENTIFIER))
            return parseVariableOrFunctionExpr();

        throw new ParseException("Expected Primary Expression", tokens.getNext());
    }

    private Ast.Expr parseLiteralExpr() throws ParseException {
        if (tokens.match("TRUE"))
            return new Ast.Expr.Literal(true);
        else if (tokens.match("FALSE"))
            return new Ast.Expr.Literal(false);
        else if (tokens.match("NIL"))
            return new Ast.Expr.Literal(null);
        else if (tokens.match(Token.Type.INTEGER)){
            var value = tokens.get(-1).literal();
            return new Ast.Expr.Literal(new BigInteger(value));
        }
        //TODO: Support exponents
        else if (tokens.match(Token.Type.DECIMAL)){
            var value = tokens.get(-1).literal();
            return new Ast.Expr.Literal(new BigDecimal(value));
        }
        else if (tokens.match(Token.Type.CHARACTER)){
            var literal = tokens.get(-1).literal();
            // Remove leading and trailing quote
            literal = literal.substring(1, literal.length()-1);
            // Turn single char in string into a char type (should also account for escapes)
            var value = literal.translateEscapes().toCharArray()[0];
            return new Ast.Expr.Literal(new Character(value));
        }
        else if (tokens.match(Token.Type.STRING)){
            var literal = tokens.get(-1).literal();
            // Remove leading and trailing quote
            literal = literal.substring(1, literal.length()-1).translateEscapes();
            return new Ast.Expr.Literal(new String(literal));
        }
        throw new ParseException("Expected Literal Expression", tokens.getNext());
    }

    private Ast.Expr parseGroupExpr() throws ParseException {
        Preconditions.checkState(tokens.match("("));
        var expr = parseExpr();
        if (expr == null)
            throw new ParseException("Missing Expression", tokens.getNext());
        if (!tokens.match(")"))
            throw new ParseException("Expected Closing Parenthesis", tokens.getNext());
        return new Ast.Expr.Group(expr);
    }

    private Ast.Expr parseObjectExpr() throws ParseException {
        Preconditions.checkState(tokens.match("OBJECT"));
        Optional<String> name =  Optional.empty();

        if (!tokens.match("DO")){
            if (tokens.match(Token.Type.IDENTIFIER))
                name = Optional.of(tokens.get(-1).literal());
            if (!tokens.match("DO"))
                throw new ParseException("Expected DO in Object expression", tokens.getNext());
        }

        var fields = new ArrayList<Ast.Stmt.Let>();
        var methods = new ArrayList<Ast.Stmt.Def>();
        while (tokens.peek("LET")) {fields.add((Ast.Stmt.Let)parseLetStmt());}
        while (tokens.peek("DEF")) {methods.add((Ast.Stmt.Def)parseDefStmt());}
        if (!tokens.match("END"))
            throw new ParseException("Expected END at the end of the Object Expression", tokens.getNext());
        return new Ast.Expr.ObjectExpr(name, fields, methods);
    }

    private Ast.Expr parseVariableOrFunctionExpr() throws ParseException {
        Preconditions.checkState(tokens.match(Token.Type.IDENTIFIER));
        var identifier = tokens.get(-1).literal();
        // Checks if function or variable
        if (tokens.match("(")){
            List<Ast.Expr> arguments = new ArrayList<>();
            // Checks if function contains arguments
            if (!tokens.match(")")){
                var expr = parseExpr();
                arguments.add(expr);
                while (tokens.match(",")){
                    expr = parseExpr();
                    arguments.add(expr);
                }
                if (!tokens.match(")")){
                    throw new ParseException("Expected Closing Parenthesis", tokens.getNext());
                }
            }
            return new Ast.Expr.Function(identifier, arguments);
        }
        return new Ast.Expr.Variable(identifier);
    }

    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at (index + offset).
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Returns the token at (index + offset).
         */
        public Token get(int offset) {
            Preconditions.checkState(has(offset));
            return tokens.get(index + offset);
        }

        /**
         * Returns the next token, if present.
         */
        public Optional<Token> getNext() {
            return index < tokens.size() ? Optional.of(tokens.get(index)) : Optional.empty();
        }

        /**
         * Returns true if the next characters match their corresponding
         * pattern. Each pattern is either a {@link Token.Type}, matching tokens
         * of that type, or a {@link String}, matching tokens with that literal.
         * In effect, {@code new Token(Token.Type.IDENTIFIER, "literal")} is
         * matched by both {@code peek(Token.Type.IDENTIFIER)} and
         * {@code peek("literal")}.
         */
        public boolean peek(Object... patterns) {
            if (!has(patterns.length - 1)) {
                return false;
            }
            for (int offset = 0; offset < patterns.length; offset++) {
                var token = tokens.get(index + offset);
                var pattern = patterns[offset];
                Preconditions.checkState(pattern instanceof Token.Type || pattern instanceof String, pattern);
                if (!token.type().equals(pattern) && !token.literal().equals(pattern)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Equivalent to peek, but also advances the token stream.
         */
        public boolean match(Object... patterns) {
            var peek = peek(patterns);
            if (peek) {
                index += patterns.length;
            }
            return peek;
        }

    }

}
