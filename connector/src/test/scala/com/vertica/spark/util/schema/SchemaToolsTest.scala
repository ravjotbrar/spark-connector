package com.vertica.spark.util.schema

import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec

import org.scalamock.scalatest.MockFactory
import java.sql.ResultSet
import java.sql.ResultSetMetaData

import com.vertica.spark.datasource.jdbc._

import org.apache.spark.sql.types._

import com.vertica.spark.util.error._
import com.vertica.spark.util.error.SchemaErrorType._
import com.vertica.spark.util.error.JdbcErrorType._

case class ColumnDef(index: Int, name: String, colType: Int, colTypeName: String, scale: Int, signed: Boolean, nullable: Boolean)

/**
  * Tests functionality of schema tools: converting schema between JDBC and Spark types.
  *
  * Tests here not exhaustive of all possible options, covers common cases and edge cases. Integration tests should cover a wider selection of closer to real world cases of table schema.
  */
class SchemaToolsTests extends AnyFlatSpec with BeforeAndAfterAll with MockFactory with org.scalatest.OneInstancePerTest {

  private def mockJdbcDeps(tablename: String): (JdbcLayerInterface, ResultSet, ResultSetMetaData) = {
    val jdbcLayer = mock[JdbcLayerInterface]
    val resultSet = mock[ResultSet]
    val rsmd = mock[ResultSetMetaData]

    (jdbcLayer.query _).expects("SELECT * FROM " + tablename + " WHERE 1=0").returning(Right(resultSet))
    (resultSet.getMetaData _).expects().returning(rsmd)
    (resultSet.close _).expects()

    (jdbcLayer, resultSet, rsmd)

  }

  private def mockColumnMetadata(rsmd: ResultSetMetaData, col: ColumnDef) = {
    (rsmd.getColumnLabel _).expects(col.index).returning(col.name)
    (rsmd.getColumnType _).expects(col.index).returning(col.colType)
    (rsmd.getColumnTypeName _).expects(col.index).returning(col.colTypeName)
    (rsmd.getScale _).expects(col.index).returning(col.scale)
    (rsmd.isSigned _).expects(col.index).returning(col.signed)
    (rsmd.isNullable _).expects(col.index).returning(if(col.nullable) ResultSetMetaData.columnNullable else ResultSetMetaData.columnNoNulls)
  }

  private def mockColumnCount(rsmd: ResultSetMetaData, count: Int) = {
    (rsmd.getColumnCount _).expects().returning(count)
  }

  val tablename = "testtable"

  val schemaTools = new SchemaTools()

  it should "parse a single-column double schema" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    // Schema
    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.REAL, "REAL", 32, signed = false, nullable = true))
    mockColumnCount(rsmd, 1)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val field = schema.fields(0)
        assert(field.name == "col1")
        assert(field.nullable)
        assert(field.dataType == DoubleType)
    }
  }

  it should "parse a multi-column schema" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    // Schema
    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.REAL, "REAL", 32, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.VARCHAR, "VARCHAR", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(3, "col3", java.sql.Types.BIGINT, "BIGINT", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 3)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields(0).name == "col1")
        assert(fields(0).nullable)
        assert(fields(0).dataType == DoubleType)

        assert(fields(1).name == "col2")
        assert(fields(1).nullable)
        assert(fields(1).dataType == StringType)

        assert(fields(2).name == "col3")
        assert(fields(2).nullable)
        assert(fields(2).dataType == LongType)
    }
  }

  it should "parse BIGINT" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    // Signed BIGINT represented by long type
    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.BIGINT, "BIGINT", 32, signed = true, nullable = true))
    // Unsigned BIGINT represented by decimal type without any digits after the 0
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.BIGINT, "BIGINT", 0, signed = false, nullable = true))
    mockColumnCount(rsmd, 2)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields(0).name == "col1")
        assert(fields(0).dataType == LongType)

        assert(fields(1).name == "col2")
        assert(fields(1).dataType == DecimalType(DecimalType.MAX_PRECISION,0))
    }
  }

  it should "parse decimal types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.NUMERIC, "NUMERIC", 32, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.DECIMAL, "VARCHAR", 16, signed = false, nullable = true))
    mockColumnCount(rsmd, 2)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields(0).name == "col1")
        assert(fields(0).dataType == DecimalType(DecimalType.MAX_PRECISION, 32))

        assert(fields(1).name == "col2")
        assert(fields(1).dataType == DecimalType(DecimalType.MAX_PRECISION, 16))
    }
  }

  it should "parse int types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.INTEGER, "INTEGER", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.SMALLINT, "SMALLINT", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(3, "col3", java.sql.Types.TINYINT, "TINYINT", 0, signed = false, nullable = true))
    mockColumnCount(rsmd, 3)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields(0).dataType == IntegerType)
        assert(fields(1).dataType == IntegerType)
        assert(fields(2).dataType == IntegerType)
    }
  }

  it should "parse binary types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.BINARY, "BINARY", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.BLOB, "BLOB", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(3, "col3", java.sql.Types.VARBINARY, "VARBINARY", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(4, "col4", java.sql.Types.LONGVARBINARY, "LONGVARBINARY", 0, signed = false, nullable = true))
    mockColumnCount(rsmd, 4)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields(0).dataType == BinaryType)

        assert(fields(1).dataType == BinaryType)

        assert(fields(2).dataType == BinaryType)

        assert(fields(3).dataType == BinaryType)
    }
  }

  it should "parse boolean types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.BOOLEAN, "BOOLEAN", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.BIT, "BIT", 0, signed = false, nullable = true))
    mockColumnCount(rsmd, 2)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields(0).name == "col1")
        assert(fields(0).dataType == BooleanType)

        assert(fields(1).name == "col2")
        assert(fields(1).dataType == BooleanType)
    }
  }

  it should "parse string types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    // All these types are strings or to be converted as such
    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.CHAR, "CHAR", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.CLOB, "CLOB", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(3, "col3", java.sql.Types.LONGNVARCHAR, "LONGNVARCHAR", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(4, "col4", java.sql.Types.LONGVARCHAR, "LONGVARCHAR", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(5, "col5", java.sql.Types.NCHAR, "NCHAR", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(6, "col6", java.sql.Types.NCLOB, "NCLOB", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(7, "col7", java.sql.Types.NVARCHAR, "NVARCHAR", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(8, "col8", java.sql.Types.REF, "REF", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(9, "col9", java.sql.Types.SQLXML, "SQLXML", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(10, "col10", java.sql.Types.STRUCT, "STRUCT", 0, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(11, "col11", java.sql.Types.VARCHAR, "VARCHAR", 0, signed = false, nullable = true))
    mockColumnCount(rsmd, 11)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields.length == 11)
        for(field <- fields) {
          assert(field.dataType == StringType)
        }
    }
  }

  it should "parse date type" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.DATE, "DATE", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 1)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields.length == 1)
        assert(fields(0).dataType == DateType)
    }
  }

  it should "parse float type" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.FLOAT, "FLOAT", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 1)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields.length == 1)
        assert(fields(0).dataType == FloatType)
    }
  }

  it should "parse timestamp type" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.TIMESTAMP, "TIMESTAMP", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 1)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields.length == 1)
        assert(fields(0).dataType == TimestampType)
    }
  }

  it should "parse rowid to long type" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.ROWID, "ROWID", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 1)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields.length == 1)
        assert(fields(0).dataType == LongType)
    }
  }

  it should "parse double types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.DOUBLE, "DOUBLE", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.REAL, "REAL", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 2)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(_) => fail
      case Right(schema) =>
        val fields = schema.fields
        assert(fields.length == 2)
        assert(fields(0).dataType == DoubleType)
        assert(fields(1).dataType == DoubleType)
    }
  }

  it should "error on unsupported types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", java.sql.Types.ARRAY, "ARRAY", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", java.sql.Types.DATALINK, "DATALINK", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(3, "col3", java.sql.Types.DISTINCT, "DISTINCT", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(4, "col4", java.sql.Types.JAVA_OBJECT, "JAVA_OBJECT", 0, signed = true, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(5, "col5", java.sql.Types.NULL, "NULL", 0, signed = true, nullable = true))
    mockColumnCount(rsmd, 5)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(errList) => assert(errList.size == 5)
      case Right(_) => fail
    }
  }

  it should "fail when trying to parse invalid types" in {
    val (jdbcLayer, _, rsmd) = mockJdbcDeps(tablename)

    mockColumnMetadata(rsmd, ColumnDef(1, "col1", 50000, "invalid-type", 16, signed = false, nullable = true))
    mockColumnMetadata(rsmd, ColumnDef(2, "col2", 50000, "invalid-type", 16, signed = false, nullable = true))
    mockColumnCount(rsmd, 2)

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(errList) =>
        assert(errList.size == 2)
        assert(errList.head.err == MissingConversionError)
        assert(errList(1).err == MissingConversionError)
      case Right(_) => fail
    }
  }

  it should "fail when there's an error connecting to database" in {
    val jdbcLayer = mock[JdbcLayerInterface]

    (jdbcLayer.query _).expects("SELECT * FROM " + tablename + " WHERE 1=0").returning(Left(JDBCLayerError(ConnectionError)))

    schemaTools.readSchema(jdbcLayer, tablename) match {
      case Left(errList) =>
        assert(errList.size == 1)
        assert(errList.head.err == JdbcError)
      case Right(_) => fail
    }
  }
}