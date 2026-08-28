# Basic Programming Language
This repository contains a basic programming language made with Java, created as an experiment of sorts to gain a better understanding of programming language concepts.

A Lexer, Parser, Evaluator and Analyzer were made for the language. Compiler was omitted due to the technical complexity of completing it. 

The grammar for the language as a whole is shown here:
```
source ::= stmt*

stmt::= let_stmt | def_stmt | if_stmt | for_stmt | return_stmt | expression_or_assignment_stmt
let_stmt ::= 'LET' identifier ('=' expr)? ';'
def_stmt ::= 'DEF' identifier '(' (identifier (',' identifier)*)? ')' 'DO' stmt* 'END'
if_stmt ::= 'IF' expr 'DO' stmt* ('ELSE' stmt*)? 'END'
for_stmt ::= 'FOR' identifier 'IN' expr 'DO' stmt* 'END'
return_stmt ::= 'RETURN' expr? ('IF' expr)? ';'
expression_or_assignment_stmt ::= expr ('=' expr)? ';'

expr ::= logical_expr
logical_expr ::= comparison_expr (('AND' | 'OR') comparison_expr)*
comparison_expr ::= additive_expr (('<' | '<=' | '>' | '>=' | '==' | '!=') additive_expr)*
additive_expr ::= multiplicative_expr (('+' | '-') multiplicative_expr)*
multiplicative_expr ::= secondary_expr (('*' | '/') secondary_expr)*

secondary_expr ::= primary_expr property_or_method*
property_or_method ::= '.' identifier ('(' (expr (',' expr)*)? ')')?

primary_expr ::= literal_expr | group_expr | object_expr | variable_or_function_expr
literal_expr ::= 'NIL' | 'TRUE' | 'FALSE' | integer | decimal | character | string
group_expr ::= '(' expr ')'
object_expr ::= 'OBJECT' identifier? 'DO' let_stmt* def_stmt* 'END'
variable_or_function_expr ::= identifier ('(' (expr (',' expr)*)? ')')?

// Lexer rules for identifier, integer, decimal, character, and string apply as expected.
```

The EBNF grammar for the Lexer specifically can be seen here:
```
tokens ::= (skipped* token)* skipped*

these rules do not emit tokens; input is skipped by the lexer
skipped ::= whitespace | comment
whitespace ::= [ \b\n\r\t]+
comment ::= '/' '/' [^\n\r]*

these rules do emit tokens
token ::= identifier | number | character | string | operator
identifier ::= [A-Za-z_] [A-Za-z0-9_-]*
number ::= [+-]? [0-9]+ ('.' [0-9]+)? ('e' [+-]? [0-9]+)?
character ::= ['] ([^'\n\r\\] | escape) [']
string ::= '"' ([^"\n\r\\] | escape)* '"'
escape ::= '\' [bnrt'"\]
operator ::= [<>!=] '='? | [^A-Za-z_0-9'" \b\n\r\t]
```

API files are labeled as such at the top of their document and are provided by the CISE department of the University of Florida.



