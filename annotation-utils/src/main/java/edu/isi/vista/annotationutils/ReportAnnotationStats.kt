package edu.isi.vista.annotationutils

import edu.isi.nlp.parameters.Parameters
import edu.isi.nlp.parameters.serifstyle.SerifStyleParameterFileLoader
import org.jetbrains.exposed.dao.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.lang.RuntimeException

fun main(argv: Array<String>) {
    if (argv.size != 1) {
        throw RuntimeException("Expected a single argument, a parameter file")
    }
    val paramsLoader = SerifStyleParameterFileLoader.Builder()
            .interpolateEnvironmentalVariables(true).build()
    val params = paramsLoader.load(File(argv[0]))
    val db = connectToInceptionDbFromParams(params)

    transaction(db) {
        logNumSearchesByUser()
        logNumAnnotationsByUser()
    }
}

fun connectToInceptionDbFromParams(params: Parameters): Database {
    val hostname = params.getString("hostName")
    val port = params.getPositiveInteger("port")
    val dbPassword = params.getString("databasePassword")

    // Inception only official supports MySql, so we hardcode it for now
    // I'm copying the magic stuff at the end of the URL which Inception itself uses when
    // accessing the database.
    return Database.connect("jdbc:mysql://$hostname:$port/inception?useSSL=false&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8",
            driver = "com.mysql.jdbc.Driver",
            user = "inception", password = dbPassword)
}

private fun logNumSearchesByUser() {
    println("# of Searches by User")
    LoggedEventTable.selectAll()
            .filter { it[LoggedEventTable.event] == "ExternalSearchQueryEvent" }
            .groupBy { it[LoggedEventTable.user] }
            .forEach { println("User ${it.key} performed ${it.value.size} searches") }
}

private fun logNumAnnotationsByUser() {
    println("# of Annotations by User")

    LoggedEventTable.selectAll()
            .filter { it[LoggedEventTable.event] == "AfterAnnotationUpdateEvent" }
            .groupBy { it[LoggedEventTable.user] }
            .forEach { println("User ${it.key} made ${it.value.size} annotations") }
}


// table schemas
/* +-----------------+--------------+------+-----+---------+----------------+
| Field           | Type         | Null | Key | Default | Extra          |
+-----------------+--------------+------+-----+---------+----------------+
| id              | bigint(20)   | NO   | PRI | NULL    | auto_increment |
| created         | datetime(6)  | YES  |     | NULL    |                |
| description     | longtext     | YES  |     | NULL    |                |
| disableExport   | bit(1)       | NO   |     | NULL    |                |
| mode            | varchar(255) | NO   |     | NULL    |                |
| name            | varchar(255) | NO   | UNI | NULL    |                |
| scriptDirection | varchar(255) | YES  |     | NULL    |                |
| updated         | datetime(6)  | YES  |     | NULL    |                |
| version         | int(11)      | NO   |     | NULL    |                |
| state           | varchar(255) | YES  |     | NULL    |                |
+-----------------+--------------+------+-----+---------+----------------+ */


/*
users

+-----------+--------------+------+-----+---------+-------+
| Field     | Type         | Null | Key | Default | Extra |
+-----------+--------------+------+-----+---------+-------+
| username  | varchar(255) | NO   | PRI | NULL    |       |
| created   | datetime(6)  | YES  |     | NULL    |       |
| email     | varchar(255) | YES  |     | NULL    |       |
| enabled   | bit(1)       | NO   |     | NULL    |       |
| lastLogin | datetime(6)  | YES  |     | NULL    |       |
| password  | varchar(255) | YES  |     | NULL    |       |
| updated   | datetime(6)  | YES  |     | NULL    |       |
+-----------+--------------+------+-----+---------+-------+
 */

/*
mysql> describe annotation_document;
+------------------+--------------+------+-----+---------+----------------+
| Field            | Type         | Null | Key | Default | Extra          |
+------------------+--------------+------+-----+---------+----------------+
| id               | bigint(20)   | NO   | PRI | NULL    | auto_increment |
| created          | datetime(6)  | YES  |     | NULL    |                |
| name             | varchar(255) | NO   | MUL | NULL    |                |
| sentenceAccessed | int(11)      | YES  |     | NULL    |                |
| state            | varchar(255) | NO   |     | NULL    |                |
| timestamp        | datetime(6)  | YES  |     | NULL    |                |
| updated          | datetime(6)  | YES  |     | NULL    |                |
| user             | varchar(255) | YES  |     | NULL    |                |
| document         | bigint(20)   | YES  | MUL | NULL    |                |
| project          | bigint(20)   | YES  | MUL | NULL    |                |
+------------------+--------------+------+-----+---------+----------------+
 */

/*
mysql> describe annotation_type;
+-------------------------+--------------+------+-----+---------+----------------+
| Field                   | Type         | Null | Key | Default | Extra          |
+-------------------------+--------------+------+-----+---------+----------------+
| id                      | bigint(20)   | NO   | PRI | NULL    | auto_increment |
| allowSTacking           | bit(1)       | YES  |     | NULL    |                |
| builtIn                 | bit(1)       | YES  |     | NULL    |                |
| crossSentence           | bit(1)       | YES  |     | NULL    |                |
| description             | longtext     | YES  |     | NULL    |                |
| enabled                 | bit(1)       | NO   |     | NULL    |                |
| linkedListBehavior      | bit(1)       | YES  |     | NULL    |                |
| lockToTokenOffset       | bit(1)       | YES  |     | NULL    |                |
| multipleTokens          | bit(1)       | YES  |     | NULL    |                |
| name                    | varchar(255) | NO   | MUL | NULL    |                |
| onClickJavascriptAction | longtext     | YES  |     | NULL    |                |
| readonly                | bit(1)       | NO   |     | NULL    |                |
| showTextInHover         | bit(1)       | YES  |     | NULL    |                |
| type                    | varchar(255) | NO   |     | NULL    |                |
| uiName                  | varchar(255) | NO   |     | NULL    |                |
| annotation_feature      | bigint(20)   | YES  | MUL | NULL    |                |
| annotation_type         | bigint(20)   | YES  |     | NULL    |                |
| project                 | bigint(20)   | YES  | MUL | NULL    |                |
| anchoring_mode          | varchar(255) | NO   |     | NULL    |                |
| validation_mode         | varchar(255) | NO   |     | never   |                |
| overlap_mode            | varchar(255) | NO   |     | NULL    |                |
+-------------------------+--------------+------+-----+---------+----------------+
21 rows in set (0.01 sec)
 */

/*
mysql> describe annotation_feature;
+-------------------------------+--------------+------+-----+---------+----------------+
| Field                         | Type         | Null | Key | Default | Extra          |
+-------------------------------+--------------+------+-----+---------+----------------+
| id                            | bigint(20)   | NO   | PRI | NULL    | auto_increment |
| description                   | longtext     | YES  |     | NULL    |                |
| enabled                       | bit(1)       | NO   |     | NULL    |                |
| hideUnconstraintFeature       | bit(1)       | YES  |     | NULL    |                |
| includeInHover                | bit(1)       | YES  |     | NULL    |                |
| link_mode                     | varchar(255) | YES  |     | NULL    |                |
| link_type_name                | varchar(255) | YES  |     | NULL    |                |
| link_type_role_feature_name   | varchar(255) | YES  |     | NULL    |                |
| link_type_target_feature_name | varchar(255) | YES  |     | NULL    |                |
| multi_value_mode              | varchar(255) | YES  |     | NULL    |                |
| name                          | varchar(255) | NO   |     | NULL    |                |
| remember                      | bit(1)       | NO   |     | NULL    |                |
| required                      | bit(1)       | NO   |     | NULL    |                |
| type                          | varchar(255) | YES  |     | NULL    |                |
| uiName                        | varchar(255) | NO   |     | NULL    |                |
| visible                       | bit(1)       | NO   |     | NULL    |                |
| annotation_type               | bigint(20)   | YES  | MUL | NULL    |                |
| project                       | bigint(20)   | YES  | MUL | NULL    |                |
| tag_set                       | bigint(20)   | YES  |     | NULL    |                |
| traits                        | longtext     | YES  |     | NULL    |                |
+-------------------------------+--------------+------+-----+---------+----------------+
 */

/*
mysql> describe logged_event;
+-----------+--------------+------+-----+-------------------+-----------------------------+
| Field     | Type         | Null | Key | Default           | Extra                       |
+-----------+--------------+------+-----+-------------------+-----------------------------+
| ID        | bigint(20)   | NO   | PRI | NULL              | auto_increment              |
| event     | varchar(255) | NO   |     | NULL              |                             |
| created   | timestamp    | NO   |     | CURRENT_TIMESTAMP | on update CURRENT_TIMESTAMP |
| user      | varchar(255) | NO   |     | NULL              |                             |
| details   | longtext     | YES  |     | NULL              |                             |
| project   | bigint(20)   | NO   |     | NULL              |                             |
| document  | bigint(20)   | NO   |     | NULL              |                             |
| annotator | varchar(255) | YES  |     | NULL              |                             |
+-----------+--------------+------+-----+-------------------+-----------------------------+
8 rows in set (0.00 sec)

+-----------------+--------------+------+-----+---------+----------------+
| Field           | Type         | Null | Key | Default | Extra          |
+-----------------+--------------+------+-----+---------+----------------+
| id              | bigint(20)   | NO   | PRI | NULL    | auto_increment |
| created         | datetime(6)  | YES  |     | NULL    |                |
| description     | longtext     | YES  |     | NULL    |                |
| disableExport   | bit(1)       | NO   |     | NULL    |                |
| mode            | varchar(255) | NO   |     | NULL    |                |
| name            | varchar(255) | NO   | UNI | NULL    |                |
| scriptDirection | varchar(255) | YES  |     | NULL    |                |
| updated         | datetime(6)  | YES  |     | NULL    |                |
| version         | int(11)      | NO   |     | NULL    |                |
| state           | varchar(255) | YES  |     | NULL    |                |
+-----------------+--------------+------+-----+---------+----------------+
 */

object ProjectDbTable : LongIdTable("project") {
    val name = varchar("name", 255)
}

object LoggedEventTable : LongIdTable("logged_event") {
    val event = varchar("event", 255)
    //val created : Column<String> = timestamp
    val user = varchar("user", 255)
    val details = text("details")
    val project = reference("project", ProjectDbTable)
}