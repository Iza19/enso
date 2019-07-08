package org.enso

import scala.reflect.macros.blackbox.Context
import scala.reflect.runtime.universe._
import scala.language.experimental.macros

class Logger {
  import Logger._

  var nesting = 0

  def log(s: String): Unit =
    macro funcRedirect

  def group[T](msg: String)(body: => T): T =
    macro groupRedirect[T]

  def trace[T](body: => T): T =
    macro targetRedirect[T]

  def _log(msg: String): Unit =
    println("|  " * nesting + msg)

  def _group[T](msg: String)(body: => T): T = {
    _log(msg)
    beginGroup()
    val out = body
    endGroup()
    out
  }

  def _trace[T](msg: String)(body: => T): T = {
    _log(msg)
    beginGroup()
    val out = body
    endGroup()
    out
  }

  def beginGroup(): Unit =
    nesting += 1

  def endGroup(): Unit =
    nesting -= 1

}

object Logger {

  def groupRedirect[R: c.WeakTypeTag](
    c: Context
  )(msg: c.Tree)(body: c.Tree): c.Expr[R] = {
    import c.universe._
    val target = c.macroApplication match {
      case Apply(Apply(TypeApply(Select(base, name), tp), msg2), body2) =>
        val newName = TermName("_" + name.toString)
        Apply(Apply(TypeApply(Select(base, newName), tp), msg2), body2)
      case _ => throw new Error("Unsupported shape")
    }
    if (checkEnabled(c)) c.Expr(q"$target") else c.Expr(q"$body")
  }

  def targetRedirect[R: c.WeakTypeTag](c: Context)(body: c.Tree): c.Expr[R] = {
    import c.universe._
    val target = c.macroApplication match {
      case Apply(TypeApply(Select(base, name), tp), body2) =>
        val newName   = TermName("_" + name.toString)
        val owner     = c.internal.enclosingOwner.asMethod
        val ownerName = Literal(Constant(owner.name.toString))
        owner.paramLists match {
          case lst :: _ =>
            val lst2 = lst.map(x => q"$x")
            val msg =
              if (lst2.isEmpty) List(q"$ownerName")
              else List(q"$ownerName + $lst2.toString().drop(4)")
            Apply(Apply(TypeApply(Select(base, newName), tp), msg), body2)
          case _ => throw new Error("Unsupported shape")
        }
      case _ => throw new Error("Unsupported shape")
    }
    if (checkEnabled(c)) c.Expr(q"$target") else c.Expr(q"$body")
  }

  def funcRedirect(c: Context)(s: c.Tree): c.Expr[Unit] = {
    import c.universe._
    val target = c.macroApplication match {
      case Apply(Select(base, name), args) =>
        val newName = TermName("_" + name.toString)
        Apply(Select(base, newName), args)
      case _ => throw new Error("Unsupported shape")
    }
    if (checkEnabled(c)) c.Expr(q"$target") else c.Expr(q"{}")
  }

  def checkEnabled(c: Context): Boolean = {
    val optPfx  = "logging"
    val opts    = c.settings.filter(_.matches(s"(\\+|\\-)$optPfx.*"))
    val owner   = c.internal.enclosingOwner.fullName
    var enabled = true
    opts.foreach { opt =>
      val sign   = opt.head
      val body   = opt.tail.drop(optPfx.length)
      val status = sign == '+'
      val applies =
        if (body == "") true
        else {
          val pathPfx = body.head
          val path    = body.tail
          pathPfx == '@' && owner.startsWith(path)
        }
      if (applies) enabled = status
    }
    enabled
  }

}