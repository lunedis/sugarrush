
import java.util.concurrent.Executors

import io.chrisdavenport.cormorant
import io.chrisdavenport.cormorant.generic.semiauto.deriveLabelledRead
import org.scalatest.{FunSuiteLike, Matchers}

import scala.concurrent.ExecutionContext

class MainTest extends FunSuiteLike with Matchers {

  test("parseSkillPlan") {
    Main.parseSkillPlan("Mechanics IV") shouldBe(("Mechanics", 4))
    Main.parseSkillPlan("Large Energy Weapons V") shouldBe(("Large Energy Weapons", 5))
    Main.parseSkillPlan("Energy Grid Upgrades III") shouldBe(("Energy Grid Upgrades", 3))
    Main.parseSkillPlan("Amarr Frigate I") shouldBe(("Amarr Frigate", 1))
    Main.parseSkillPlan("Science II") shouldBe(("Science", 2))
  }

  test("skillPlanToSkills") {
    Main.skillListToMap(
      List(("Mechanics", 5),("Mechanics", 4), ("Large Energy Weapons", 3), ("Large Energy Weapons", 4))
    ) shouldBe(Map("Mechanics" -> 5, "Large Energy Weapons" -> 4))
  }

  test("parseSkillboardLine") {
    Main.parseSkillboardLine(
      "Controlled Bursts / Rank 2 / Level: 5 / SP: 512,000 of 512,000"
    ) shouldBe Some(("Controlled Bursts", 5))

    Main.parseSkillboardLine(
      "Infomorph Synchronizing / Rank 2 / Level: 3 / SP: 16,000 of 512,000"
    ) shouldBe Some(("Infomorph Synchronizing", 3))

    Main.parseSkillboardLine(
      ""
    ) shouldBe None
  }

  test("spAtLevel") {
    Main.spAtLevel(1) shouldBe 250
    Main.spAtLevel(2) shouldBe 1414
    Main.spAtLevel(3) shouldBe 8000
    Main.spAtLevel(4) shouldBe 45255
    Main.spAtLevel(5) shouldBe 256000
  }

  test("sp") {
    val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))

    implicit val skills = Main.getSkills(ec).unsafeRunSync()

    Main.spForSkill("Mechanics", 4) shouldBe 45255

    Main.spForSkill("Caldari Titan", 5) shouldBe 4096000

    Main.spForSkill("Capacitor Emission Systems", 4) shouldBe 90510
  }

  test("spdiff") {
    val ec = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(2))

    implicit val skills = Main.getSkills(ec).unsafeRunSync()

    Main.spDiff(
      Map("Mechanics" -> 4, "Hull Upgrades" -> 5, "Navigation" -> 5),
      Map("Caldari Titan" -> 5, "Hull Upgrades" -> 5, "Navigation" -> 4)
    ) shouldBe((256000, 4096000))
  }

}
