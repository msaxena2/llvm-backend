package org.kframework.backend.llvm.matching

import org.kframework.parser.kore.{Sort,SymbolOrAlias}
import org.kframework.parser.kore.implementation.{DefaultBuilders => B}
import org.kframework.backend.llvm.matching.pattern._
import org.kframework.backend.llvm.matching.dt._
import java.util
import java.util.HashMap

class Column(val fringe: Fringe, val patterns: List[Pattern[String]]) {
  def category: SortCategory = {
    val ps = patterns.map(_.category).filter(_.isDefined)
    if (ps.isEmpty) {
      SymbolS()
    } else {
      ps.head.get
    }
  }

  private def rawScore(clauses: List[Clause]): Double = {
    val raw = patterns.head.score(fringe, clauses.head, patterns.tail, clauses.tail)
    assert(!raw.isNaN)
    raw
  }

  def score(matrix: Matrix): Double = {
    val raw = rawScore(matrix.clauses)
    if (raw != 0.0) {
      raw
    } else {
      val unboundMapColumns = matrix.columns.filter(col => col.rawScore(matrix.clauses).isNegInfinity)
      val unboundPatterns = unboundMapColumns.map(_.patterns).transpose
      val keys = unboundPatterns.map(_.flatMap(_.mapOrSetKeys))
      val vars = keys.map(_.flatMap(_.variables))
      val boundVars = patterns.map(_.variables)
      val intersection = (vars, boundVars).zipped.map(_.intersect(_))
      val needed = intersection.exists(_.nonEmpty)
      if (needed) {
        Double.MinPositiveValue
      } else {
        0.0
      }
    }
  }

  def signature(clauses: Seq[Clause]): List[Constructor] = {
    val used = patterns.zipWithIndex.flatMap(p => p._1.signature(clauses(p._2)))
    val bestUsed = bestKey(clauses) match {
      case None => used
      case Some(k) => used.filter(_.isBest(k))
    }
    val usedInjs = bestUsed.flatMap(fringe.injections)
    val dups = if (fringe.isExact) bestUsed else bestUsed ++ usedInjs
    val nodups = dups.distinct.toList
    if (nodups.contains(Empty())) {
      List(Empty())
    } else {
      nodups.filter(_ != Empty())
    }
  }

  def maxListSize: (Int, Int) = {
    val listPs = patterns.filter(_.isInstanceOf[ListP[String]]).map(_.asInstanceOf[ListP[String]])
    val longestHead = listPs.map(_.head.size).max
    val longestTail = listPs.map(_.tail.size).max
    (longestHead, longestTail)
  }

  def bestKey(clauses: Seq[Clause]): Option[Pattern[Option[Occurrence]]] = {
    for ((pat, clause, i) <- (patterns, clauses, clauses.indices).zipped) {
      if (!pat.isWildcard) {
        return pat.bestKey(fringe, clause, patterns.drop(i), clauses.drop(i))
      }
    }
    None
  }

  def expand(ix: Constructor, clauses: Seq[Clause]): Seq[Column] = {
    val fringes = fringe.expand(ix)
    val ps = (patterns, clauses).zipped.toIterable.map(t => t._1.expand(ix, fringes, fringe, t._2))
    (fringes, ps.transpose).zipped.toSeq.map(t => new Column(t._1, t._2.toList))
  }
}

class VariableBinding[T](val name: T, val category: SortCategory, val occurrence: Occurrence) {}

class Fringe(val symlib: Parser.SymLib, val sort: Sort, val occurrence: Occurrence, val isExact: Boolean) { 
  val sortInfo = SortInfo(sort, symlib)

  lazy val category: SortCategory = SortCategory(Parser.getStringAtt(symlib.sortAtt(sort), "hook"))

  lazy val length: Int = category.length(sortInfo.constructors.size)

  def overloads(sym: SymbolOrAlias): Seq[SymbolOrAlias] = {
    symlib.overloads.getOrElse(sym, Seq())
  }

  def injections(ix: Constructor): Seq[Constructor] = {
    ix match {
      case SymbolC(sym) =>
        if (symlib.overloads.contains(sym) ||sym.ctr == "inj") {
          sortInfo.trueInjMap(sym).map(SymbolC)
        } else {
          Seq()
        }
      case _ => Seq()
    }
  }

  def contains(ix: Constructor): Boolean = {
    lookup(ix).isDefined
  }

  def expand(ix: Constructor): Seq[Fringe] = {
    lookup(ix).get
  }

  def lookup(ix: Constructor): Option[Seq[Fringe]] = {
    ix.expand(this)
  }
}

class SortInfo private(sort: Sort, symlib: Parser.SymLib) {
  val constructors = symlib.symbolsForSort.getOrElse(sort, Seq())
  private val rawInjections = constructors.filter(_.ctr == "inj")
  private val injMap = rawInjections.map(b => (b, rawInjections.filter(a => symlib.isSubsorted(a.params.head, b.params.head)))).toMap
  private val rawOverloads = constructors.filter(symlib.overloads.contains)
  private val overloadMap = rawOverloads.map(s => (s, symlib.overloads(s))).toMap
  private val overloadInjMap = overloadMap.map(e => (e._1, e._2.map(g => B.SymbolOrAlias("inj", Seq(symlib.signatures(g)._2, symlib.signatures(e._1)._2)))))
  val trueInjMap = injMap ++ overloadInjMap
}
object SortInfo {
  private val cache = new util.HashMap[Sort, SortInfo]()
  def apply(sort: Sort, symlib: Parser.SymLib): SortInfo = {
    cache.computeIfAbsent(sort, s => new SortInfo(s, symlib))
  }
}

class Action(val ordinal: Int, val rhsVars: Seq[String], val scVars: Option[Seq[String]]) {}

class Clause(
  // the rule to be applied if this row succeeds
  val action: Action,
  // the variable bindings made so far while matching this row
  val bindings: Seq[VariableBinding[String]],
  // the length of the head and tail of any list patterns
  // with frame variables bound so far in this row
  val listRanges: Seq[(Occurrence, Int, Int)],
  // variable bindings to injections that need to be constructed
  // since they do not actually exist in the original subject term
  val overloadChildren: Seq[(Constructor, VariableBinding[String])]) {

  lazy val bindingsMap: Map[String, VariableBinding[String]] = bindings.groupBy(_.name).mapValues(_.head)
  lazy val boundOccurrences: Set[Occurrence] = bindings.map(_.occurrence).toSet

  def canonicalize(name: String): Option[Occurrence] = {
    bindingsMap.get(name).map(_.occurrence)
  }
  def canonicalize[T](pat: Pattern[T]): Option[Pattern[Option[Occurrence]]] = {
    if (pat.isBound(this)) {
      Some(pat.canonicalize(this))
    } else {
      None
    }
  }

  def addVars(ix: Option[Constructor], pat: Pattern[String], f: Fringe): Clause = {
    new Clause(action, bindings ++ pat.bindings(ix, f.occurrence), listRanges ++ pat.listRange(ix, f.occurrence), overloadChildren ++ pat.overloadChildren(f, ix, Num(0, f.occurrence)))
  }
}

class Row(val patterns: Seq[Pattern[String]], val clause: Clause) {
  // returns whether the row is done matching
  def isWildcard: Boolean = patterns.forall(_.isWildcard)

  def expand(colIx: Int): Seq[Row] = {
    val p0s = patterns(colIx).expandOr
    p0s.map(p => new Row(patterns.take(colIx) ++ Seq(p) ++ patterns.takeRight(patterns.size - colIx - 1), clause))
  }
}

class Matrix private(val symlib: Parser.SymLib, private val rawColumns: Seq[Column], private val rawRows: List[Row], private val rawClauses: List[Clause], private val rawFringe: Seq[Fringe], expanded: Boolean) {

  val columns: Seq[Column] = {
    if (rawColumns != null) {
      rawColumns
    } else if (rawRows.isEmpty) {
      rawFringe.map(f => new Column(f, List()))
    } else {
      val ps = rawRows.map(_.patterns).transpose
      (rawFringe, ps).zipped.toSeq.map(col => new Column(col._1, col._2.toList))
    }
  }

  val rows: List[Row] = {
    if (rawRows != null) {
      rawRows
    } else if (rawColumns.isEmpty) {
      rawClauses.map(clause => new Row(Seq(), clause))
    } else {
      val ps = rawColumns.map(_.patterns).transpose
      (ps, rawClauses).zipped.toList.map(row => new Row(row._1, row._2))
    }
  }

  val clauses: List[Clause] = {
    if (rawClauses != null) {
      rawClauses
    } else {
      rows.map(_.clause)
    }
  }

  val fringe: Seq[Fringe] = {
    if (rawFringe != null) {
      rawFringe
    } else {
      columns.map(_.fringe)
    }
  }

  def this(symlib: Parser.SymLib, cols: Seq[(Sort, List[Pattern[String]])], actions: List[Action]) {
    this(symlib, (cols, (1 to cols.size).map(i => new Fringe(symlib, cols(i - 1)._1, Num(i, Base()), false))).zipped.toSeq.map(pair => new Column(pair._2, pair._1._2)), null, actions.map(new Clause(_, Seq(), Seq(), Seq())), null, false)
  }

  // compute the column with the best score, choosing the first such column if they are equal
  lazy val bestColIx: Int = {
    columns.zipWithIndex.maxBy(_._1.score(this))._2
  }

  lazy val bestCol: Column = columns(bestColIx)

  lazy val sigma: List[Constructor] = bestCol.signature(clauses)

  def specialize(ix: Constructor): (String, Matrix) = {
    val filtered = filterMatrix(Some(ix), (c, p) => p.isSpecialized(ix, bestCol.fringe, c))
    val expanded = Matrix.fromColumns(symlib, filtered.columns(bestColIx).expand(ix, filtered.clauses) ++ filtered.notBestCol(bestColIx), filtered.clauses)
    (ix.name, expanded)
  }

  def cases: List[(String, Matrix)] = sigma.map(specialize)

  lazy val compiledCases: Seq[(String, DecisionTree)] = cases.map(l => (l._1, l._2.compile))

  def filterMatrix(ix: Option[Constructor], checkPattern: (Clause, Pattern[String]) => Boolean): Matrix = {
    val newRows = rows.filter(row => checkPattern(row.clause, row.patterns(bestColIx))).map(row => new Row(row.patterns, row.clause.addVars(ix, row.patterns(bestColIx), columns(bestColIx).fringe)))
    Matrix.fromRows(symlib, newRows, fringe, expanded = false)
  }

  lazy val default: Option[Matrix] = {
    if (bestCol.category.hasIncompleteSignature(sigma, bestCol.fringe)) {
      val defaultConstructor = {
        if (sigma.contains(Empty())) Some(NonEmpty())
        else if (sigma.isEmpty) None
        else {
          lazy val (hd, tl) = bestCol.maxListSize
          sigma.head match {
            case ListC(sym,_) => Some(ListC(sym, hd + tl))
            case _ => None
          }
        }
      }
      val filtered = filterMatrix(defaultConstructor, (_, p) => p.isDefault)
      val expanded = if (defaultConstructor.isDefined) {
        if (bestCol.category.isExpandDefault) {
          Matrix.fromColumns(symlib, filtered.columns(bestColIx).expand(defaultConstructor.get, filtered.clauses) ++ filtered.notBestCol(bestColIx), filtered.clauses)
        } else {
          Matrix.fromColumns(symlib, filtered.notBestCol(bestColIx), filtered.clauses)
        }
      } else {
        Matrix.fromColumns(symlib, filtered.notBestCol(bestColIx), filtered.clauses)
      }
      Some(expanded)
    } else {
      None
    }
  }

  lazy val compiledDefault: Option[DecisionTree] = default.map(_.compile)

  def getLeaf(row: Row, child: DecisionTree): DecisionTree = {
    def makeEquality(category: SortCategory, os: (Occurrence, Occurrence), dt: DecisionTree): DecisionTree = {
      Function(category.equalityFun, Equal(os._1, os._2), Seq(os._1, os._2), "BOOL.Bool",
        SwitchLit(Equal(os._1, os._2), 1, Seq(("1", dt), ("0", child)), None))
    }
    // first, add all remaining variable bindings to the clause
    val vars = row.clause.bindings ++ columns.flatMap(col => col.patterns.head.bindings(None, col.fringe.occurrence))
    val overloadVars = row.clause.overloadChildren.map(_._2)
    val allVars = vars ++ overloadVars
    // then group the bound variables by their name
    val grouped = allVars.groupBy(v => (v.name, v.category)).mapValues(_.map(_.occurrence))
    // compute the variables bound more than once
    val nonlinear = grouped.filter(_._2.size > 1)
    val nonlinearPairs = nonlinear.mapValues(l => (l, l.tail).zipped)
    // get some random occurrence bound for each variable in the map
    val deduped = grouped.toSeq.map(e => (e._1._1, e._2.head))
    // sort alphabetically by variable name
    val sorted = deduped.sortWith(_._1 < _._1)
    // filter by the variables used in the rhs
    val filtered = sorted.filter(v => row.clause.action.rhsVars.contains(v._1))
    val newVars = filtered.map(_._2)
    val atomicLeaf = Leaf(row.clause.action.ordinal, newVars)
    // check that all occurrences of the same variable are equal
    val nonlinearLeaf = nonlinearPairs.foldRight[DecisionTree](atomicLeaf)((e, dt) => e._2.foldRight(dt)((os,dt2) => makeEquality(e._1._2, os, dt2)))
    val sc = row.clause.action.scVars match {
      // if there is no side condition, continue
      case None => nonlinearLeaf
      case Some(cond) =>
        // filter by the variables used in the side condition
        val condFiltered = sorted.filter(t => cond.contains(t._1))
        val condVars = condFiltered.map(_._2)
        val newO = SC(row.clause.action.ordinal)
        // evaluate the side condition and if it is true, continue, otherwise go to the next row
        Function("side_condition_" + row.clause.action.ordinal, newO, condVars, "BOOL.Bool",
          SwitchLit(newO, 1, Seq(("1", nonlinearLeaf), ("0", child)), None))
    }
    // fill out the bindings for list range variables
    val withRanges = row.clause.listRanges.foldRight(sc)({
      case ((o @ Num(_, o2), hd, tl), dt) => Function("hook_LIST_range_long", o, Seq(o2, Lit(hd.toString, "MINT.MInt 64"), Lit(tl.toString, "MINT.MInt 64")), "LIST.List", dt)
    })
    row.clause.overloadChildren.foldRight(withRanges)({
      case ((SymbolC(inj), v),dt) => MakePattern(v.occurrence, SymbolP(inj, Seq(VariableP(Some(v.occurrence.asInstanceOf[Inj].rest), v.category))), dt)
    })
  }

  def expand: Matrix = {
    if (columns.isEmpty) {
      new Matrix(symlib, rawColumns, rawRows, rawClauses, rawFringe, true)
    } else {
      Matrix.fromRows(symlib, rows.flatMap(_.expand(bestColIx)), fringe, expanded = true)
    }
  }

  def compile: DecisionTree = {
    if (!expanded) {
      return expand.compile
    }
    if (rows.isEmpty)
      Failure()
    else {
      if (rows.head.isWildcard) {
        // if there is only one row left, then try to match it and fail the matching if it fails
        // otherwise, if it fails, try to match the remainder of hte matrix
        getLeaf(rows.head, notFirstRow.compile)
      } else {
        // compute the sort category of the best column
        bestCol.category.tree(this)
      }
    }
  }

  def notFirstRow: Matrix = {
    Matrix.fromRows(symlib, rows.tail, fringe, expanded = false)
  }

  def notBestCol(colIx: Int): Seq[Column] = {
    columns.take(colIx) ++ columns.takeRight(columns.size - colIx - 1)
  }
}

object Matrix {
  def fromRows(symlib: Parser.SymLib, rows: List[Row], fringe: Seq[Fringe], expanded: Boolean): Matrix = {
    new Matrix(symlib, null, rows, null, fringe, expanded)
  }

  def fromColumns(symlib: Parser.SymLib, cols: Seq[Column], clauses: List[Clause]): Matrix = {
    new Matrix(symlib, cols, null, clauses, null, false)
  }
}