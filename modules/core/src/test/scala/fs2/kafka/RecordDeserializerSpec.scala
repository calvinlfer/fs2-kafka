package fs2.kafka

import cats.effect.IO

class RecordDeserializerSpec extends BaseSpec {

  import cats.effect.unsafe.implicits.global

  describe("RecordDeserializer#transform") {
    it("should transform the RecordDeserializer applying the function to inner Deserializers") {

      val strRecordDes: RecordDeserializer[IO, String] =
        RecordDeserializer
          .lift(Deserializer[IO, Int])
          .transform(_.map(_.toString))

      strRecordDes.forKey
        .use(_.deserialize("T1", Headers.empty, serializeToBytes(1)))
        .unsafeRunSync() shouldBe "1"
    }
  }

  describe("RecordDeserializer#attempt") {
    it(
      "should transform the RecordDeserializer[F, T] to RecordDeserializer[F, Either[Throwable, T]]"
    ) {

      val attemptIntRecordDes: RecordDeserializer[IO, Either[Throwable, Int]] =
        RecordDeserializer
          .lift(Deserializer[IO, Int].flatMap {
            case 1 => Deserializer[IO, Int]
            case _ => Deserializer.failWith[IO, Int]("Unsupported value")
          })
          .attempt

      attemptIntRecordDes.forKey
        .use(_.deserialize("T1", Headers.empty, serializeToBytes(1)))
        .unsafeRunSync() shouldBe Right(1)

      attemptIntRecordDes.forKey
        .use(_.deserialize("T1", Headers.empty, null))
        .unsafeRunSync()
        .isLeft shouldBe true
    }
  }

  describe("RecordDeserializer#option") {
    it("should transform the RecordDeserializer[F, T] to RecordDeserializer[F, Option[T]]") {

      val optIntRecordDes: RecordDeserializer[IO, Option[Int]] =
        RecordDeserializer
          .lift(Deserializer[IO, Int])
          .option

      optIntRecordDes.forKey
        .use(_.deserialize("T1", Headers.empty, serializeToBytes(1)))
        .unsafeRunSync() shouldBe Some(1)

      optIntRecordDes.forKey
        .use(_.deserialize("T1", Headers.empty, null))
        .unsafeRunSync() shouldBe None
    }
  }

  private def serializeToBytes[T: Serializer[IO, *]](value: T): Array[Byte] =
    Serializer[IO, T].serialize("", Headers.empty, value).unsafeRunSync()
}