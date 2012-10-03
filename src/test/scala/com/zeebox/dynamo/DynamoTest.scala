package com.zeebox.dynamo

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, FreeSpec}
import com.amazonaws.services.dynamodb.model.AttributeValue
import java.util.UUID
import akka.util.duration._

object DynamoTestDataObjects{
  case class DynamoTestObject(id:String, someValue:String)

  implicit object DynamoTestDO extends DynamoObject[DynamoTestObject]{
    def toDynamo(t: DynamoTestObject) = Map("id"->t.id, "someValue"->t.someValue)
    def fromDynamo(a: Map[String, AttributeValue]) = DynamoTestObject(a("id").getS, a("someValue").getS)
    protected val table = "dynamotest_"+util.Random.alphanumeric.filter(_.isLetter).take(10).mkString
  }

  case class Broken(id:String)
  implicit object BrokenDO extends DynamoObject[Broken]{
    def toDynamo(t: Broken) = Map()
    def fromDynamo(a: Map[String, AttributeValue]) = Broken("wiejfi")
    protected def table = "nonexistenttable"
  }
}

class DynamoTest extends FreeSpec with MustMatchers with BeforeAndAfterAll{
  implicit val dynamo = Dynamo(DynamoConfig(System.getProperty("amazon.accessKey"), System.getProperty("amazon.secret"), "devng_", System.getProperty("dynamo.url", "https://dynamodb.eu-west-1.amazonaws.com")), 3)
  implicit val timeout = 10 seconds
  import DynamoTestDataObjects._


  "Save/Get" in {
    assertCanSaveGetObject()
  }

  "Get returns None if record not found" in {
    assert( Read[DynamoTestObject](UUID.randomUUID().toString).blockingExecute === None )
  }

  "Reading from non-existent table causes ThirdPartyException" in {
    intercept[ThirdPartyException]{
      Read[Broken](UUID.randomUUID().toString).blockingExecute
    }.getMessage must include("resource not found")
  }

  "Client survives 100 parallel errors" in {
    (1 to 100).par.foreach{ i =>
      intercept[ThirdPartyException]{
        Read[Broken](UUID.randomUUID().toString).blockingExecute
      }
    }
    assertCanSaveGetObject()
  }

  "Delete" in {
    val obj = DynamoTestObject(UUID.randomUUID().toString, "some test value" + math.random)
    (dynamo ? Save(obj)).get
    DeleteById[DynamoTestObject](obj.id).blockingExecute

    Read[DynamoTestObject](obj.id).blockingExecute must be ('empty)
  }

  private def assertCanSaveGetObject() {
    val obj = DynamoTestObject(UUID.randomUUID().toString, "some test value" + math.random)
    (dynamo ? Save(obj)).get

    val saved = Read[DynamoTestObject](obj.id).blockingExecute.get
    assert(saved === obj)
  }

  override protected def beforeAll() {
    super.beforeAll()
    dynamo.start()
    CreateTable[DynamoTestObject]().blockingExecute(dynamo,1 minute)
  }

  override protected def afterAll() {
    super.afterAll()
    DeleteTable[DynamoTestObject].blockingExecute
    dynamo.stop()
  }
}

