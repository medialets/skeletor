import org.specs._
import com.rpetrich.szkieletor.{Cassandra, Rows, Keyspace, ColumnFamily}
import java.util.UUID
import me.prettyprint.hector.api.query.{MultigetSubSliceQuery, SuperSliceQuery, MultigetSliceQuery, MultigetSliceCounterQuery,CounterQuery,RangeSlicesQuery}
import com.rpetrich.szkieletor.Conversions._
import me.prettyprint.hector.api.{ConsistencyLevelPolicy}
import com.rpetrich.szkieletor.{CL}

class SkeletorSpec extends Specification {

	val cassandra = new Cassandra("skeletor-spec", "localhost:9160")

	val TestColumnFamily = cassandra \ "FixtureTestSkeletor" \ "TestColumnFamily" //now setup the initial CF
	val CounterTestColumnFamily = cassandra \ "FixtureTestSkeletor" \ "CounterTestColumnFamily" //now setup the initial Counter CF
	val MultiRowTestColumnFamily = cassandra \ "FixtureTestSkeletor" \ "MultiRowTestColumnFamily"

	val SuperColumnTestFamily = cassandra \ "FixtureTestSkeletor" \ "SuperColumnTestFamily"
	SuperColumnTestFamily.setSuper()

	doBeforeSpec {
	}

	doAfterSpec {
		cassandra.shutdown();
	}

	//create random and unique row, column and value strings for setting and reading to make sure we are dealing with data for this test run
	def rnv(): (String, String, String) = {
		("row_" + UUID.randomUUID().toString(), "column_" + UUID.randomUUID().toString(), "value_" + UUID.randomUUID().toString())
	}

	var defaultReadConsistencyLevel: ConsistencyLevelPolicy = {
		CL.ONE()
	}

	"Skeletor " should  {

		"be able to create a super column family" in {
			val ksp = Keyspace(cassandra, "FixtureTestSkeletor")
			val columnFamily = ColumnFamily(ksp, "SuperColumnFamily")
			columnFamily.setSuper()

			// these both work as expected
			// 	columnFamily.delete
			// 	columnFamily.create

			columnFamily.isSuper mustEqual true
		}


		"be able to add two rows together into the first" in {
			val cv1 = (TestColumnFamily -> "rowKey1" has "columnName1" of "columnValue1")

			var rows1:Rows = Rows(cv1) //add the row to the rows object

			(rows1.size == 1) must beTrue

			val cv2 = (TestColumnFamily -> "rowKey2" has "columnName2" of "columnValue2")

			var rows2:Rows = Rows(cv2) //add the row to the rows object

			rows2.size mustEqual 1

			rows1 ++ rows2  //add the second Rows into the first Rows, Rows1 becomes the new rows

			rows2.size mustEqual 1 //make sure rows 2 is still 1

			rows1.size mustEqual 2 //and rows1 is now equal to 2
		}

		"be able to write to a Cassandra Super Column with a list" in {

			val key = UUID.randomUUID().toString()
			val columnName = "tags"
			val tags = List("ok", "then", "super")
			var cv = (SuperColumnTestFamily -> key has columnName of tags)

			var rows:Rows = Rows(cv)

			cassandra.defaultWriteConsistencyLevel = defaultReadConsistencyLevel
			cassandra << rows

			var results = List[String]()
			def check {
				results must haveTheSameElementsAs(tags)
			}

			var count = 0
			def processRow(r:String, c:String, v:String) {
				results = results ++ List(c)
				v mustEqual ""
				count += 1

				if (count == tags.length) check
			}

			def sets(mgssq: MultigetSubSliceQuery[String, String, String, String]) {
				mgssq.setKeys(key)
				mgssq.setRange("", "", false, 3)
				mgssq.setSuperColumn(columnName)
			}

			SuperColumnTestFamily.multigetSubSliceQuery(sets, processRow)
		}

		"be able to read top level super columns" in {
			val key = UUID.randomUUID().toString()
			val columnName1 = "tags"
			val columnName2 = "places"
			val tags1 = List("ok", "then", "super")
			val tags2 = List("here", "there", "everywhere")
			var cv1 = (SuperColumnTestFamily -> key has columnName1 of tags1)
			var cv2 = (SuperColumnTestFamily -> key has columnName2 of tags2)

			var rows:Rows = Rows(cv1) ++ Rows(cv2)

			cassandra.defaultWriteConsistencyLevel = defaultReadConsistencyLevel
			cassandra << rows

			var results = Set[String]()
			def check {
				results must haveTheSameElementsAs(List(columnName1, columnName2))
			}

			var count = 0
			def processRow(r:String, c:String, v:String) {
				results = results ++ Set(r)
				v mustEqual ""
				count += 1

				if (results.size == 2) check
			}

			def sets(mgssq: SuperSliceQuery[String, String, String, String]) {
				mgssq.setKey(key)
				mgssq.setRange("", "", false, 3)
			}

			SuperColumnTestFamily.superSliceQuery(sets, processRow)
		}

		"write to Cassandra and read row key" in {

			val randRow = rnv()
			val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
			val columnName = randRow._2 //lets take some random unique string to write and verify reading it
			val columnValue = randRow._3 //lets take some random unique string to write and verify reading it

			var cv = (TestColumnFamily -> rowKey has columnName of columnValue) //create a column value for a row for this column family

			var rows:Rows = Rows(cv) //add the row to the rows object

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			//println("push the row=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName + " and value=" + columnValue)
			cassandra << rows

			def processRow(r:String, c:String, v:String) = {
				(r == rowKey) must beTrue
				(c == columnName) must beTrue
				(v == columnValue) must beTrue
			}

			def sets(mgsq: MultigetSliceQuery[String, String, String]) {
				mgsq.setKeys(rowKey) //we want to pull out the row key we just put into Cassandra
				mgsq.setColumnNames(columnName) //and just this column
			}

			TestColumnFamily >> (sets, processRow) //get data out of Cassandra and process it

		}

		"write mutliple rows and retrieve them" in {
			var shouldBeResultList = List[String]()
			val r = scala.util.Random
			val columnName = "columnName" + r.nextInt.toString
			val list = List.fill(10) {
				val columnValue = "columnValue" + r.nextInt.toString
				shouldBeResultList = shouldBeResultList ++ List(columnValue)
				Rows((MultiRowTestColumnFamily -> ("rowKey" + r.nextInt.toString) has columnName of columnValue))
			}

			val rows = list.reduce { (rows, newRow) => rows ++ newRow; rows }

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			cassandra << rows

			var resultList = List[String]()

			def processRow(r:String, c:String, v:String) = {
				resultList = resultList ++ List(v)
			}

			def sets(rsq: RangeSlicesQuery[String, String, String]) {
				rsq.setKeys("", "")
				// rsq.setRowCount(rows)
				rsq.setRange("", "", false, 3)
				rsq.setColumnNames(columnName)
			}

			MultiRowTestColumnFamily >>> (sets, processRow)

			resultList.length mustEqual(10)
			resultList must haveTheSameElementsAs(shouldBeResultList)
		}

		"increment a counter and read the values back with a multi get slice" in {
			val randRow = rnv()
			val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
			val columnName = randRow._2 //lets take some random unique string to write and verify reading it

			val col = CounterTestColumnFamily -> rowKey has columnName

			var cv = (col inc)
			var rows:Rows = Rows(cv) //add the row to the rows object

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			//println("push the row counter=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName)
			cassandra << rows //push the row into Cassandra, batch mutate

			def processRow(r:String, c:String, v:Long) = {
				//println("processRowCounter="+r+"["+c+"]="+v)
				(r == rowKey) must beTrue
				(c == columnName) must beTrue
				(v == 1) must beTrue
			}

			def sets(mgsq: MultigetSliceCounterQuery[String, String]) {
				mgsq.setKeys(rowKey) //we want to pull out the row key we just put into Cassandra
				mgsq.setColumnNames(columnName) //and just this column
			}

			CounterTestColumnFamily ># (sets, processRow)  //get data out of Cassandra and process it

		}

		"increment a counter and read the values back by row and column" in {
			val randRow = rnv()
			val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
			val columnName = randRow._2 //lets take some random unique string to write and verify reading it

			val col = CounterTestColumnFamily -> rowKey has columnName

			var cv = (col inc)
			var rows:Rows = Rows(cv) //add the row to the rows object

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			//println("push the row counter=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName)
			cassandra << rows //push the row into Cassandra, batch mutate

			def setsGet(cq: CounterQuery[String, String]) {
				cq.setKey(rowKey) //we want to pull out the row key we just put into Cassandra
				cq.setName(columnName) //and just this column
			}

			def processGetRow(v:Long) = {
				//println("processRowCounter="+v)
				(v == 1) must beTrue
			}

			CounterTestColumnFamily >% (setsGet, processGetRow)  //get data out of Cassandra and process it
		}

		"increment a counter by more than 1 and read the values back by row and column" in {
			val randRow = rnv()
			val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
			val columnName = randRow._2 //lets take some random unique string to write and verify reading it

			val col = CounterTestColumnFamily -> rowKey has columnName

			var cv = (col inc 6)
			var rows:Rows = Rows(cv) //add the row to the rows object

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			//println("push the row counter=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName)
			cassandra << rows //push the row into Cassandra, batch mutate

			def setsGet(cq: CounterQuery[String, String]) {
				cq.setKey(rowKey) //we want to pull out the row key we just put into Cassandra
				cq.setName(columnName) //and just this column
			}

			def processGetRow(v:Long) = {
				//println("processRowCounter="+v)
				(v == 6) must beTrue
			}

			CounterTestColumnFamily >% (setsGet, processGetRow)  //get data out of Cassandra and process it
		}

		"decrement a counter and read the values back by row and column" in {
			val randRow = rnv()
			val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
			val columnName = randRow._2 //lets take some random unique string to write and verify reading it

			val col = CounterTestColumnFamily -> rowKey has columnName

			var cv = (col dec)
			var rows:Rows = Rows(cv) //add the row to the rows object

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			//println("push the row counter=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName)
			cassandra << rows //push the row into Cassandra, batch mutate

			def setsGet(cq: CounterQuery[String, String]) {
				cq.setKey(rowKey) //we want to pull out the row key we just put into Cassandra
				cq.setName(columnName) //and just this column
			}

			def processGetRow(v:Long) = {
				//println("processRowCounter="+v)
				(v == -1) must beTrue
			}

			CounterTestColumnFamily >% (setsGet, processGetRow)  //get data out of Cassandra and process it
		}

		"decrement a counter by more than 1 and read the values back by row and column" in {
			val randRow = rnv()
			val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
			val columnName = randRow._2 //lets take some random unique string to write and verify reading it

			val col = CounterTestColumnFamily -> rowKey has columnName

			var cv = (col dec 7)
			var rows:Rows = Rows(cv) //add the row to the rows object

			cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
			//println("push the row counter=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName)
			cassandra << rows //push the row into Cassandra, batch mutate

			def setsGet(cq: CounterQuery[String, String]) {
				cq.setKey(rowKey) //we want to pull out the row key we just put into Cassandra
				cq.setName(columnName) //and just this column
			}

			def processGetRow(v:Long) = {
				//println("processRowCounter="+v)
				(v == -7) must beTrue
			}

			CounterTestColumnFamily >% (setsGet, processGetRow)  //get data out of Cassandra and process it
		}

		// more info on consitency settings = http://www.datastax.com/docs/0.8/dml/data_consistency
		"be able to tune consistency" in {

			"new default for all reads and another for writes" in {

				//new default read consistency
				var defaultReadConsistencyLevel: ConsistencyLevelPolicy = {
					CL.ANY()
				}

				cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel

				//new default write consistency
				var defaultWriteConsistencyLevel: ConsistencyLevelPolicy = {
					CL.QUARUM()
				}

				cassandra.defaultWriteConsistencyLevel = defaultWriteConsistencyLevel

				true must beTrue //TODO: some better test than just everything getting to this point without exception
			}

			"read and write explicitly with ALL consistency"  in {
				var cv = (TestColumnFamily -> "rowKey" has "columnName" of "columnValue")
				var rows:Rows = Rows(cv) //add the row to the rows object

				var testdefaultReadConsistencyLevel: ConsistencyLevelPolicy = {
					CL.ANY()
				}

				cassandra.defaultReadConsistencyLevel = testdefaultReadConsistencyLevel
				//println("push the row=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName + " and value=" + columnValue)
				cassandra << (rows, CL.ALL())

				true must beTrue //TODO: some better test than just everything getting to this point without exception
			}

			"delete an entire row" in {
				val randRow = rnv()
				val rowKey = randRow._1 //lets take some random unique string to write and verify reading it
				val columnName = randRow._2 //lets take some random unique string to write and verify reading it
				val columnValue = randRow._3 //lets take some random unique string to write and verify reading it

				var cv = (TestColumnFamily -> rowKey has columnName of columnValue) //create a column value for a row for this column family

				var rows:Rows = Rows(cv) //add the row to the rows object

				cassandra.defaultReadConsistencyLevel = defaultReadConsistencyLevel
				//println("push the row=" + rowKey + " into Cassandra, batch mutate counter column=" + columnName + " and value=" + columnValue)
				cassandra << rows

				def processRow(r:String, c:String, v:String) = {
					(r == rowKey) must beTrue
					(c == columnName) must beTrue
					(v == columnValue) must beTrue
				}

				def sets(mgsq: MultigetSliceQuery[String, String, String]) {
					mgsq.setKeys(rowKey) //we want to pull out the row key we just put into Cassandra
					mgsq.setColumnNames(columnName) //and just this column
				}

				TestColumnFamily >> (sets, processRow) //get data out of Cassandra and process it

				def deletedRow(r:String, c:String, v:String) = {
					(r != rowKey) must beTrue
					(c != columnName) must beTrue
					(v != columnValue) must beTrue
				}

				cassandra delete rows.rows(0)

				TestColumnFamily >> (sets, deletedRow) //get data out of Cassandra and process it
			}
		}
	}
}