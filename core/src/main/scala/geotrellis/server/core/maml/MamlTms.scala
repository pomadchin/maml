package geotrellis.server.core.maml

import geotrellis.server.core.maml.reification._
import MamlTmsReification.ops._

import com.azavea.maml.util.Vars
import com.azavea.maml.error._
import com.azavea.maml.ast._
import com.azavea.maml.ast.codec.tree._
import com.azavea.maml.eval._
import com.typesafe.scalalogging.LazyLogging
import io.circe._
import io.circe.syntax._
import cats._
import cats.data.{NonEmptyList => NEL}
import cats.effect._
import cats.implicits._
import geotrellis.raster._
import geotrellis.raster.Tile


/** Provides methods for producing TMS tiles */
object MamlTms extends LazyLogging {

  /**
   * Given an [[Expression]], a parameter map, and an interpreter, create a function
   *  which takes z, x, and y coordinates and returns the corresponding tile.
   *
   * @tparam Param a type whose instances can refer to layers
   * @param getExpression an [[IO]] yielding a description of the map algebra
   *                      to be carried out
   * @param getParams an [[IO]] yielding a map from source node ID to some stand-in
   *                  for a tile source
   * @param interpreter a MAML-compliant interpreter (with buffering)
   * @return a function from (Int, Int, Int) to a Tile corresponding to the Param provided
   */
  def apply[Param](
    getExpression: IO[Expression],
    getParams: IO[Map[String, Param]],
    interpreter: BufferingInterpreter
  )(
    implicit reify: MamlTmsReification[Param],
             enc: Encoder[Param],
             contextShift: ContextShift[IO]
  ): (Int, Int, Int) => IO[Interpreted[Tile]] = (z: Int, x: Int, y: Int) => {
    for {
      expr             <- getExpression
      _                <- IO.pure(logger.info(s"Retrieved MAML AST at TMS ($z, $x, $y): ${expr.asJson.noSpaces}"))
      paramMap         <- getParams
      _                <- IO.pure(logger.info(s"Retrieved parameters for TMS ($z, $x, $y): ${paramMap.asJson.noSpaces}"))
      vars             <- IO.pure { Vars.varsWithBuffer(expr) }
      params           <- vars.toList.parTraverse { case (varName, (_, buffer)) =>
                            val thingify = paramMap(varName).tmsReification(buffer)
                            thingify(z, x, y).map(varName -> _)
                          } map { _.toMap }
      reified          <- IO.pure { Expression.bindParams(expr, params) }
    } yield reified.andThen(interpreter(_)).andThen(_.as[Tile])
  }


  /** Provide a function to produce an expression given a set of arguments and an
    *  IO for getting arguments; getting back a tile
    */
  def generateExpression[Param](
    mkExpr: Map[String, Param] => Expression,
    getParams: IO[Map[String, Param]],
    interpreter: BufferingInterpreter
  )(
    implicit reify: MamlTmsReification[Param],
             enc: Encoder[Param],
             contextShift: ContextShift[IO]
  ) = apply[Param](getParams.map(mkExpr(_)), getParams, interpreter)


  /** Provide an expression and expect arguments to fulfill its needs */
  def curried[Param](
    expr: Expression,
    interpreter: BufferingInterpreter
  )(
    implicit reify: MamlTmsReification[Param],
             enc: Encoder[Param],
             contextShift: ContextShift[IO]
  ): (Map[String, Param], Int, Int, Int) => IO[Interpreted[Tile]] =
    (paramMap: Map[String, Param], z: Int, x: Int, y: Int) => {
      val thingify = apply[Param](IO.pure(expr), IO.pure(paramMap), interpreter)
      thingify(z, x, y)
    }


  /** The identity endpoint (for simple display of raster) */
  def identity[Param](
    interpreter: BufferingInterpreter
  )(
    implicit reify: MamlTmsReification[Param],
             enc: Encoder[Param],
             contextShift: ContextShift[IO]
  ) = curried(RasterVar("identity"), interpreter)

}

