/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package unit.connectors

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{eq => ameq, _}
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import play.api.http.HeaderNames
import play.mvc.Http.MimeTypes
import uk.gov.hmrc.customs.api.common.config.{ServiceConfig, ServiceConfigProvider}
import uk.gov.hmrc.customs.inventorylinking.export.connectors.MdgExportsConnector
import uk.gov.hmrc.customs.inventorylinking.export.logging.ExportsLogger
import uk.gov.hmrc.customs.inventorylinking.export.services.WSHttp
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, NotFoundException}
import uk.gov.hmrc.play.test.UnitSpec
import util.MockitoPassByNameHelper.PassByNameVerifier
import util.RequestHeaders
import util.TestData._

import scala.concurrent.{ExecutionContext, Future}

class MdgExportsConnectorSpec extends UnitSpec with MockitoSugar with BeforeAndAfterEach with Eventually {

  val mockWsPost = mock[WSHttp]
  val mockExportsLogger = mock[ExportsLogger]
  val mockServiceConfigProvider = mock[ServiceConfigProvider]

  val connector = new MdgExportsConnector(mockWsPost, mockExportsLogger, mockServiceConfigProvider)

  val serviceConfig = ServiceConfig("the-url", Some("bearerToken"), "default")

  val xml = <xml></xml>
  implicit val hc = HeaderCarrier().withExtraHeaders(RequestHeaders.API_SUBSCRIPTION_FIELDS_ID_HEADER)

  private val httpException = new NotFoundException("Emulated 404 response from a web call")

  override protected def beforeEach() {
    reset(mockWsPost, mockServiceConfigProvider, mockExportsLogger)

    when(mockServiceConfigProvider.getConfig("mdg-exports")).thenReturn(serviceConfig)
  }

  val date: DateTime = new DateTime(2017, 7, 4, 13, 45, DateTimeZone.UTC)

  val httpFormattedDate = "Tue, 04 Jul 2017 13:45:00 UTC"

  "MdgExportsConnector" can {

    "when making a successful request" should {

      "use stub service url" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        verify(mockWsPost).POSTString(ameq(serviceConfig.url), anyString, any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext])
      }

      "pass the xml in the body" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        verify(mockWsPost).POSTString(anyString, ameq(xml.toString()), any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext])
      }

      "set the content type header" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        val headersCaptor: ArgumentCaptor[HeaderCarrier] = ArgumentCaptor.forClass(classOf[HeaderCarrier])
        verify(mockWsPost).POSTString(anyString, anyString, any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), headersCaptor.capture(), any[ExecutionContext])
        headersCaptor.getValue.extraHeaders should contain(HeaderNames.CONTENT_TYPE -> (MimeTypes.XML + "; charset=UTF-8"))
      }

      "set the accept header" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        val headersCaptor: ArgumentCaptor[HeaderCarrier] = ArgumentCaptor.forClass(classOf[HeaderCarrier])
        verify(mockWsPost).POSTString(anyString, anyString, any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), headersCaptor.capture(), any[ExecutionContext])
        headersCaptor.getValue.extraHeaders should contain(HeaderNames.ACCEPT -> MimeTypes.XML)
      }

      "set the date header" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        val headersCaptor: ArgumentCaptor[HeaderCarrier] = ArgumentCaptor.forClass(classOf[HeaderCarrier])
        verify(mockWsPost).POSTString(anyString, anyString, any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), headersCaptor.capture(), any[ExecutionContext])
        headersCaptor.getValue.extraHeaders should contain(HeaderNames.DATE -> httpFormattedDate)
      }

      "set the X-Forwarded-Host header" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        val headersCaptor: ArgumentCaptor[HeaderCarrier] = ArgumentCaptor.forClass(classOf[HeaderCarrier])
        verify(mockWsPost).POSTString(anyString, anyString, any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), headersCaptor.capture(), any[ExecutionContext])
        headersCaptor.getValue.extraHeaders should contain(HeaderNames.X_FORWARDED_HOST -> "MDTP")
      }

      "set the X-Correlation-Id header" in {
        returnResponseForRequest(Future.successful(mock[HttpResponse]))

        awaitSubscriptionFields

        val headersCaptor: ArgumentCaptor[HeaderCarrier] = ArgumentCaptor.forClass(classOf[HeaderCarrier])
        verify(mockWsPost).POSTString(anyString, anyString, any[Seq[(String, String)]])(
          any[HttpReads[HttpResponse]](), headersCaptor.capture(), any[ExecutionContext])
        headersCaptor.getValue.extraHeaders should contain("X-Correlation-ID" -> correlationIdValue)
      }
    }

    "when making an failing request" should {
      "propagate an underlying error when MDG call fails with a non-http exception" in {
        returnResponseForRequest(Future.failed(emulatedServiceFailure))

        val caught = intercept[EmulatedServiceFailure] {
          awaitSubscriptionFields
        }
        caught shouldBe emulatedServiceFailure

        verifyErrorLogged(caught)
      }

      "wrap an underlying error when MDG call fails with an http exception" in {
        returnResponseForRequest(Future.failed(httpException))

        val caught = intercept[RuntimeException] {
          awaitSubscriptionFields
        }
        caught.getCause shouldBe httpException

        verifyErrorLogged(caught)
      }
    }
  }

  private def verifyErrorLogged(caught: Throwable) = {
    eventually {
      PassByNameVerifier(mockExportsLogger, "error")
        .withByNameParam[String](s"Call to inventory linking exports failed. url = ${serviceConfig.url}")
        .withByNameParam[Throwable](caught)
        .withAnyHeaderCarrierParam
        .verify()
    }
  }

  private def awaitSubscriptionFields = {
    await(connector.send(xml, date, correlationIdUuid))
  }

  private def returnResponseForRequest(eventualResponse: Future[HttpResponse]) = {
    when(mockWsPost.POSTString(anyString, anyString, any[Seq[(String, String)]])(
      any[HttpReads[HttpResponse]](), any[HeaderCarrier](), any[ExecutionContext]))
      .thenReturn(eventualResponse)
  }
}
