package effekt

import effekt.lexer.*
import effekt.lexer.TokenKind.{ `::` as _, * }

import effekt.source.*

import kiama.util.Positions

import scala.annotation.{ tailrec, targetName }
import scala.util.matching.Regex
import scala.language.implicitConversions


case class ParseError2(message: String, position: Int) extends Throwable(message, null, false, false)

class RecursiveDescentParsers(positions: Positions, tokens: Seq[Token]) {

  import scala.collection.mutable.ListBuffer

  def fail(message: String): Nothing = throw ParseError2(message, position)

  // always points to the latest non-space position
  var position: Int = 0

  def peek: Token = tokens(position)
  def peek(offset: Int): Token = tokens(position + 1)
  def peek(kind: TokenKind): Boolean =
    peek.kind == kind
  def peek(offset: Int, kind: TokenKind): Boolean =
    peek(offset).kind == kind

  def next(): Token =
    val t = tokens(position)
    skip()
    t

  /**
   * Skips the current token and then all subsequent whitespace
   */
  def skip(): Unit = { position += 1; spaces() }

  @tailrec
  final def spaces(): Unit = peek.kind match {
    case TokenKind.Space => position += 1; spaces()
    case TokenKind.Comment(_) => position += 1; spaces()
    case TokenKind.Newline => position += 1; spaces()
    case _ => ()
  }

  def consume(kind: TokenKind): Unit =
    val t = next()
    if (t.kind != kind) fail(s"Expected ${kind}, but got ${t}")

  private def expect[T](expected: String)(f: PartialFunction[TokenKind, T]): T =
    val t = next()
    val kind = t.kind
    if f.isDefinedAt(kind) then f(kind) else fail(s"Expected ${expected}")

  /**
   * Guards `thn` by token `t` and consumes the token itself, if present.
   */
  inline def when[T](t: TokenKind)(thn: => T)(els: => T): T =
    if peek(t) then { consume(t); thn } else els


  /**
   * Tiny combinator DSL to sequence parsers
   */
  case class ~[+T, +U](_1: T, _2: U) {
    override def toString = s"(${_1}~${_2})"
  }

  extension [A](self: A) {
    @targetName("seq")
    inline def ~[B](other: B): (A ~ B) = new ~(self, other)
  }

  extension (self: TokenKind) {
    @targetName("seqRightToken")
    inline def ~>[R](other: => R): R = { consume(self); other }
  }

  extension (self: Unit) {
    @targetName("seqRightUnit")
    inline def ~>[R](other: => R): R = { other }
  }

  /**
   * Statements
   */
  def stmts(): Stmt = peek.kind match {
    case _ if isDefinition => DefStmt(definition(), semi() ~> stmts())
    case `with` => withStmt()
    case `var`  => DefStmt(varDef(), semi() ~> stmts())
    case `return` => `return` ~> Return(expr())
    case _ =>
      val e = expr()
      val returnPosition = peek(`}`) || peek(`case`) || peek(EOF) // TODO EOF is just for testing
      if returnPosition then Return(e)
      else ExprStmt(e, { semi(); stmts() })
  }

  // ATTENTION: here the grammar changed (we added `with val` to disambiguate)
  // with val <ID> (: <TYPE>)? = <EXPR>; <STMTS>
  // with val (<ID> (: <TYPE>)?...) = <EXPR>
  // with <EXPR>; <STMTS>
  def withStmt(): Stmt = `with` ~> peek.kind match {
    case `val` => ???
    case _ => expr() ~ (semi() ~> stmts()) match {
       case m@MethodCall(receiver, id, tps, vargs, bargs) ~ body =>
         Return(MethodCall(receiver, id, tps, vargs, bargs :+ (BlockLiteral(Nil, Nil, Nil, body))))
       case c@Call(callee, tps, vargs, bargs) ~ body =>
         Return(Call(callee, tps, vargs, bargs :+ (BlockLiteral(Nil, Nil, Nil, body))))
       case Var(id) ~ body =>
         val tgt = IdTarget(id)
         Return(Call(tgt, Nil, Nil, (BlockLiteral(Nil, Nil, Nil, body)) :: Nil))
       case term ~ body =>
         Return(Call(ExprTarget(term), Nil, Nil, (BlockLiteral(Nil, Nil, Nil, body)) :: Nil))
    }
  }

  // TODO
  def semi(): Unit =
    consume(`;`)

  def stmt(): Stmt =
    if peek(`{`) then braces { stmts() }
    else when(`return`) { Return(expr()) } { Return(expr()) }

  def isDefinition: Boolean = peek.kind match {
    case `val` | `fun` | `def` | `type` | `effect` | `namespace` => true
    case `extern` | `effect` | `interface` | `type` | `record` =>
      val kw = peek.kind
      fail(s"Only supported on the toplevel: ${kw.toString} declaration.")
    case _ => false
  }

  def definition(): Def = peek.kind match {
    case `val` => valDef()
    case _ => fail("Expected definition")
  }

  // TODO matchdef
  //  lazy val matchDef: P[Stmt] =
  //     `val` ~> matchPattern ~ many(`and` ~> matchGuard) ~ (`=` ~/> expr) ~ (`else` ~> stmt).? ~ (`;;` ~> stmts) ^^ {
  //       case p ~ guards ~ sc ~ default ~ body =>
  //        Return(Match(sc, List(MatchClause(p, guards, body)), default)) withPositionOf p
  //     }
  def valDef(): Def =
    ValDef(`val` ~> idDef(), typeAnnotationOpt(), `=` ~> stmt())

  def varDef(): Def =
    (`var` ~> idDef()) ~ typeAnnotationOpt() ~ when(`in`) { Some(idRef()) } { None } ~ (`=` ~> stmt()) match {
      case id ~ tpe ~ Some(reg) ~ expr => RegDef(id, tpe, reg, expr)
      case id ~ tpe ~ None ~ expr      => VarDef(id, tpe, expr)
    }

  def typeAnnotationOpt(): Option[ValueType] =
    if peek(`:`) then Some(typeAnnotation()) else None

  def typeAnnotation(): ValueType = ???

  def expr(): Term = peek.kind match {
    case `if`     => ifExpr()
    case `while`  => whileExpr()
    case `do`     => doExpr()
    case `try`    => ???
    case `region` => ???
    case `fun`    => ???
    case `box`    => ???
    case `unbox`  => ???
    case `new`    => ???
    case _ => callExpr()
  }

  def ifExpr(): Term =
    If(`if` ~> parens { matchGuards() },
      stmt(),
      when(`else`) { stmt() } { Return(UnitLit()) })

  def whileExpr(): Term =
    While(`while` ~> parens { matchGuards() },
      stmt(),
      when(`else`) { Some(stmt()) } { None })

  def doExpr(): Term =
    (`do` ~> idRef()) ~ arguments() match {
      case id ~ (targs, vargs, bargs) => Do(None, id, targs, vargs, bargs)
    }

  def matchGuards() = some(matchGuard, `and`)
  def matchGuard(): MatchGuard =
    MatchGuard.BooleanGuard(expr())

//    ( expr ~ (`is` ~/> matchPattern) ^^ MatchGuard.PatternGuard.apply
//    | expr ^^ MatchGuard.BooleanGuard.apply
//    )

  /**
   * This is a compound production for
   *  - member selection <EXPR>.<NAME>
   *  - method calls <EXPR>.<NAME>(...)
   *  - function calls <EXPR>(...)
   *
   * This way expressions like `foo.bar.baz()(x).bam.boo()` are
   * parsed with the correct left-associativity.
   */
  def callExpr(): Term = {
    var e = primExpr()

    while (peek(`.`) || isArguments)
      peek.kind match {
        // member selection (or method call)
        //   <EXPR>.<NAME>
        // | <EXPR>.<NAME>( ... )
        case `.` =>
          consume(`.`)
          val member = idRef()
          // method call
          if (isArguments) {
            val (targs, vargs, bargs) = arguments()
            e = Term.MethodCall(e, member, targs, vargs, bargs)
          } else {
            e = Term.Select(e, member)
          }

        // function call
        case _ if isArguments =>
          val callee = e match {
            case Term.Var(id) => IdTarget(id)
            case other => ExprTarget(other)
          }
          val (targs, vargs, bargs) = arguments()
          e = Term.Call(callee, Nil, vargs, Nil)

        // nothing to do
        case _ => ()
      }

    e
  }

  // TODO right now we only parse value arguments
  def isArguments: Boolean = peek(`(`) || peek(`[`) || peek(`{`)
  def arguments(): (List[ValueType], List[Term], List[Term]) =
    val vargs = many(expr, `(`, `,`, `)`)
    (Nil, vargs, Nil)

  //  lazy val arguments: P[(List[ValueType] ~ List[Term] ~ List[Term])] =
  //      ( maybeTypeArgs ~ valueArgs ~ blockArgs
  //      | maybeTypeArgs ~ valueArgs ~ success(List.empty[Term])
  //      | maybeTypeArgs ~ success(List.empty[Term]) ~ blockArgs
  //      )

  def primExpr(): Term = peek.kind match {
    case _ if isLiteral      => literal()
    case _ if isVariable     => variable()
    case _ if isHole         => hole()
    case _ if isTupleOrGroup => tupleOrGroup()
    case _ if isListLiteral  => listLiteral()
    case _ => fail("Expected variables, literals, tuples, lists, holes or group.")
  }

  def isListLiteral: Boolean = peek.kind match {
    case `[` => true
    case _ => false
  }
  def listLiteral(): Term =
    many(expr, `[`, `,`, `]`).foldRight(NilTree) { ConsTree }

  private def NilTree: Term =
    Call(IdTarget(IdRef(List(), "Nil")), Nil, Nil, Nil)

  private def ConsTree(el: Term, rest: Term): Term =
    Call(IdTarget(IdRef(List(), "Cons")), Nil, List(el, rest), Nil)

  def isTupleOrGroup: Boolean = peek(`(`)
  def tupleOrGroup(): Term =
    some(expr, `(`, `,`, `)`) match {
      case e :: Nil => e
      case xs => Call(IdTarget(IdRef(List("effekt"), s"Tuple${xs.size}")), Nil, xs.toList, Nil)
    }

  // TODO complex holes, named holes, etc.
  def isHole: Boolean = peek(`<>`)
  def hole(): Term = `<>` ~> Hole(Return(UnitLit()))

  def isLiteral: Boolean = peek.kind match {
    case _: (Integer | Float | Str) => true
    case `true` => true
    case `false` => true
    case _ => isUnitLiteral
  }
  def literal(): Literal = peek.kind match {
    case Integer(v)         => skip(); IntLit(v)
    case Float(v)           => skip(); DoubleLit(v)
    case Str(s, multiline)  => skip(); StringLit(s)
    case `true`             => skip(); BooleanLit(true)
    case `false`            => skip(); BooleanLit(false)
    case t if isUnitLiteral => skip(); skip(); UnitLit()
    case t => fail("Expected a literal")
  }

  // Will also recognize ( ) as unit if we do not emit space in the lexer...
  private def isUnitLiteral: Boolean = peek(`(`) && peek(1, `)`)

  def isVariable: Boolean = isIdRef
  def variable(): Term = Var(idRef())

  def isIdRef: Boolean = isIdent

  // TODO also parse paths
  def idRef(): IdRef = IdRef(Nil, ident())
  def idDef(): IdDef = IdDef(ident())

  //  identRef ^^ { path =>
  //    val ids = path.split("::").toList
  //    IdRef(ids.init, ids.last)
  //  }

  def isIdent: Boolean = peek.kind match {
    case Ident(id) => true
    case _ => false
  }
  def ident(): String = expect("identifier") { case Ident(id) => id }

  inline def some[T](p: () => T, before: TokenKind, sep: TokenKind, after: TokenKind): List[T] =
    consume(before)
    val res = some(p, sep)
    consume(after)
    res

  inline def some[T](p: () => T, sep: TokenKind): List[T] =
    val components: ListBuffer[T] = ListBuffer.empty
    components += p()
    while (peek(sep)) {
      consume(sep)
      components += p()
    }
    components.toList

  inline def parens[T](p: => T): T =
    consume(`(`)
    val res = p
    consume(`)`)
    res

  inline def braces[T](p: => T): T =
    consume(`{`)
    val res = p
    consume(`}`)
    res

  inline def many[T](p: () => T, before: TokenKind, sep: TokenKind, after: TokenKind): List[T] =
    consume(before)
    if (peek(after)) {
      consume(after)
      Nil
    } else {
      val components: ListBuffer[T] = ListBuffer.empty
      components += p()
      while (peek(sep)) {
        consume(sep)
        components += p()
      }
      consume(after)
      components.toList
    }

}
