import cats._
import cats.implicits._
import java.nio.file.Paths
import java.util.concurrent.Executors

import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import fs2.{io => fs2io, _}
import _root_.io.chrisdavenport.cormorant
import _root_.io.chrisdavenport.cormorant.generic.semiauto._
import _root_.io.chrisdavenport.cormorant.parser._
import _root_.io.chrisdavenport.cormorant.implicits._
import _root_.io.chrisdavenport.cormorant.LabelledRead
import org.http4s.Uri
import org.http4s._
import net.ruippeixotog.scalascraper.browser.JsoupBrowser
import net.ruippeixotog.scalascraper.model._
import net.ruippeixotog.scalascraper.dsl.DSL._
import net.ruippeixotog.scalascraper.dsl.DSL.Extract._
import net.ruippeixotog.scalascraper.dsl.DSL.Parse._

import cats.effect.Console.io._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global


case class Skill(typeId: Int, typeName: String, groupId: Int, groupName: String, rank: Int)

object Main extends IOApp {


  val romans = Map[String, Int]("I" -> 1, "II" -> 2, "III" -> 3, "IV" -> 4, "V" -> 5)

  override def run(args: List[String]): IO[ExitCode] = {
    val EXTRACTOR = 371000000
    val INJECTOR = 714000000

    val io = for {
      input <- readInput

      _ <- putStrLn(f"Analysis for ${input._1}")

      skills: Map[String, Skill] <- getSkills(ExecutionContext.global)

      planList: List[(String, Int)] <-
        fs2io.file
          .readAll[IO](Paths.get("skillplan.txt"), ExecutionContext.global, 4096)
          .through(fs2.text.utf8Decode)
          .through(fs2.text.lines)
          .map(parseSkillPlan)
          .compile.toList

      plan = skillListToMap(planList)

      characterList: List[(String, Int)] <-
        getCharacterFromUri(input)

      character = skillListToMap(characterList)

      sp = spDiff(plan, character)(skills)

      injectors = math.ceil(sp._1 / 400000) * INJECTOR
      extractors = math.floor(sp._2 / 500000) * (INJECTOR - EXTRACTOR)

      _ <- putStrLn(f"SP still to inject: ${sp._1}%,1.0f")
      _ <- putStrLn(f"extractable SP:     ${sp._2}%,1.0f")
      _ <- putStrLn(f"Difference price:   ${injectors-extractors}%,1.2f")
    } yield sp
    io.as(ExitCode.Success)
  }

  def readInput(): IO[(String, Option[String])] = for {
    input <- readLn
    split = input.split(" ")
    path = Uri.unsafeFromString((split(0))).path
    split2 = path.split("/")
    name = split2(split2.length - 1)
    password = if (split.length > 1) Some(split(1)) else None
  } yield (name, password)

  def getSkills(ec: ExecutionContext): IO[Map[String, Skill]] = {
    import _root_.io.chrisdavenport.cormorant
    import _root_.io.chrisdavenport.cormorant.generic.semiauto._

    implicit val lr: cormorant.LabelledRead[Skill] = deriveLabelledRead

    fs2.io.file
      .readAll[IO](Paths.get("skills.csv"), ec, 4096)
      .through(fs2.text.utf8Decode)
      .through(fs2.text.lines)
      .through(cormorant.fs2.readLabelled[IO, Skill])
      .map(s => s.typeName -> s)
      .compile.toList.map(_.toMap)
  }


  def print[A](x: A): IO[A] = IO.delay {
    println(x)
    x
  }

  def parseSkillPlan(line: String): (String, Int) = {
    val split = line.split(" ")
    val levelRoman = split.takeRight(1).head
    val level = romans.get(levelRoman).get
    var name = line.take(line.length - levelRoman.length - 1)
    name -> level
  }

  def skillListToMap(skillList: List[(String, Int)]): Map[String, Int] =
    skillList.groupBy(t => t._1).map(x => x._1 -> x._2.map(t => t._2).max)

  def getCharacterFromUri(input: (String, Option[String])): IO[List[(String, Int)]] = for {
    doc <- getDocument(input)
    lines = doc >> texts("tr.known_skill td")
    skills = lines.map(parseSkillboardLine).flatten.toList
  } yield skills

  def getDocument(input: (String, Option[String])): IO[Document] = IO.delay {
    val browser = JsoupBrowser()
    input match {
      case (name, Some(password)) =>
        browser.post(
          (Uri.uri("https://eveskillboard.com/password") / name).toString,
          Map("passwordSend" -> password))
      case (name, None) =>
        browser.get((Uri.uri("https://eveskillboard.com/pilot") / name).toString)
    }
  }

  def parseSkillboardLine(line: String): Option[(String, Int)] = {
    val split = line.split(" / ")
    if(split.length == 1) return None
    val name = split.head
    val levelString = split(2)
    val split2 = levelString.split(" ")
    val level = split2(1).toInt
    Some((name, level))
  }

  def spDiff(plan: Map[String, Int], character: Map[String, Int])(implicit skills: Map[String, Skill]): (Int, Int) = {
    val toSkill = plan -- character.keySet
    val tooMuch = character -- plan.keySet

    val toSkillsSP = spForSkills(toSkill)
    val tooMuchSP = spForSkills(tooMuch)

    val overlap = plan.keySet.intersect(character.keySet).map(k => k ->((plan(k), character(k)))).toMap

    val overlapToSkill = overlap.filter { case (skill, (level1, level2)) => level1 > level2}
    val overlapToMuch = overlap.filter { case (skill, (level1, level2)) => level1 < level2}

    val overlapToSkillSP = overlapToSkill.map { case (skill, (level1, level2)) => spForSkill(skill, level1) - spForSkill(skill, level2)}.sum
    val overlapTooMuchSP = overlapToMuch.map { case (skill, (level1, level2)) => spForSkill(skill, level2) - spForSkill(skill, level1)}.sum


    (toSkillsSP+overlapToSkillSP,tooMuchSP+overlapTooMuchSP)
  }

  def spAtLevel(level: Int): Int =
    Math.round(250 * Math.pow(math.sqrt(32),(level - 1))).toInt

  def spForSkill(str: String, i: Int)(implicit skills: Map[String, Skill]) =
    spAtLevel(i) * skills.get(str).map(_.rank).getOrElse(0)

  def spForSkills(s: Map[String, Int])(implicit skills: Map[String, Skill]) =
    s.map { case (skill, level) => spForSkill(skill, level) }.sum

}
