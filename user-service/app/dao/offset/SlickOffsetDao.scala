package dao.offset

import java.util.UUID

import com.ruchij.shared.monads.MonadicUtils.OptionTWrapper
import com.ruchij.shared.utils.SystemUtilities
import exceptions.FatalDatabaseException
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import scalaz.OptionT
import scalaz.std.scalaFuture.futureInstance
import services.triggering.models.{Offset, OffsetType}
import slick.jdbc.JdbcProfile
import slick.lifted.{CanBeQueryCondition, ProvenShape}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

@Singleton
class SlickOffsetDao @Inject()(override protected val dbConfigProvider: DatabaseConfigProvider)(
  implicit systemUtilities: SystemUtilities
) extends HasDatabaseConfigProvider[JdbcProfile]
    with OffsetDao {

  import dao.SlickMappedColumns.{dateTimeMappedColumn, enumMappedColumn}
  import dbConfig.profile.api._

  class OffsetTable(tag: Tag) extends Table[Offset](tag, SlickOffsetDao.TABLE_NAME) {
    def id: Rep[UUID] = column[UUID]("id", O.PrimaryKey)
    def createdAt: Rep[DateTime] = column[DateTime]("created_at")
    def offsetType: Rep[OffsetType] = column[OffsetType]("offset_type")
    def value: Rep[Long] = column[Long]("value")
    def lockAcquiredAt: Rep[Option[DateTime]] = column[Option[DateTime]]("lock_acquired_at")

    def * : ProvenShape[Offset] =
      (id, createdAt, offsetType, value, lockAcquiredAt) <> (Offset.apply _ tupled, Offset.unapply)
  }

  val offsets = TableQuery[OffsetTable]

  override def insert(offset: Offset)(implicit executionContext: ExecutionContext): Future[Offset] =
    db.run(offsets += offset)
      .flatMap { _ =>
        getById(offset.id) ifEmpty Future.failed(FatalDatabaseException)
      }

  override def acquireOffsetLock(
    offsetType: OffsetType
  )(implicit executionContext: ExecutionContext): OptionT[Future, Offset] =
    for {
      latestOffset <- getLatestOffset(offsetType)
      offset <- acquireOffsetLock(latestOffset.id)
    } yield offset

  override def releaseOffsetLock(offsetType: OffsetType, offsetValue: Long)(
    implicit executionContext: ExecutionContext
  ): OptionT[Future, Offset] =
    OptionT {
      db.run {
          offsets
            .filter(
              offset =>
                offset.offsetType === offsetType && offset.value === offsetValue && offset.lockAcquiredAt.nonEmpty
            )
            .map(_.lockAcquiredAt)
            .update(None)
        }
        .flatMap {
          case 1 => getBySelector(offset => offset.offsetType === offsetType && offset.value === offsetValue).run
          case _ => Future.successful(None)
        }
    }

  override def getLatestOffset(
    offsetType: OffsetType
  )(implicit executionContext: ExecutionContext): OptionT[Future, Offset] =
    getBySelector(_.offsetType === offsetType)

  def getById(id: UUID)(implicit executionContext: ExecutionContext): OptionT[Future, Offset] =
    getBySelector(_.id === id)

  private def getBySelector[A <: Rep[_]: CanBeQueryCondition](
    selector: OffsetTable => A
  )(implicit executionContext: ExecutionContext): OptionT[Future, Offset] =
    OptionT {
      db.run {
          offsets.filter(selector).sortBy(_.createdAt.desc).result
        }
        .map(_.headOption)
    }

  private def acquireOffsetLock(id: UUID)(implicit executionContext: ExecutionContext): OptionT[Future, Offset] =
    OptionT {
      db.run {
          offsets
            .filter(offset => offset.id === id && offset.lockAcquiredAt.isEmpty)
            .map(_.lockAcquiredAt)
            .update(Some(systemUtilities.currentTime()))
        }
        .flatMap {
          case 1 => getById(id).run
          case _ => Future.successful(None)
        }
    }
}

object SlickOffsetDao {
  val TABLE_NAME = "offsets"
}
