package com.zailinn.c001apk

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.zailinn.c001apk.ui.C001apkApp
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** An event topic's 活动详情 cards and a product's 参数 tab, from synthetic responses shaped like the official ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TopicTabsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun app(route: (okhttp3.Request) -> String) = AppContainer(
        ApplicationProvider.getApplicationContext(), "topic-tabs-${UUID.randomUUID()}",
        Interceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(route(request).toResponseBody("application/json".toMediaType())).build()
        },
    )

    private fun waitText(text: String) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun eventTopicShowsItsDetailSections() {
        val app = app { request ->
            when {
                request.url.encodedPath.endsWith("/topic/newTagDetail") -> """{"data":{"id":"7","entityType":"topic","title":"合成活动",
                    "tabList":[{"title":"有奖活动","page_name":"diy1","url":"V8_SYNTHETIC_EVENT","entityType":"page"}],"selectedTab":"diy1"}}"""
                request.url.queryParameter("url") == "V8_SYNTHETIC_EVENT" && request.url.queryParameter("page") == "1" -> FixtureTransport.list(
                    """{"entityType":"card","entityTemplate":"imageScaleCard","entityId":1,"entities":[{"entityType":"imageScale","title":"活动图片",
                        "url":"/feed/writer?type=feed&targetType=topic&tag=合成活动","pic":"https://image.coolapk.com/test/banner.jpg@1080x240.jpeg"}]}""",
                    """{"entityType":"card","entityTemplate":"textCard","entityId":2,"title":"活动介绍","description":"带话题发布动态即可参与。\n第二行说明"}""",
                    """{"entityType":"card","entityTemplate":"configCard","entityId":3,"title":"默认配置（勿动）"}""",
                )
                else -> FixtureTransport.list()
            }
        }
        compose.setContent { C001apkApp(app, Uri.parse("https://www.coolapk.com/t/合成活动")) {} }
        waitText("活动介绍")
        compose.onNodeWithText("带话题发布动态即可参与。\n第二行说明").assertIsDisplayed()
        compose.onNodeWithText("默认配置（勿动）").assertDoesNotExist()
    }

    @Test
    fun productParamsListVersionsAndKeepGroupHeadings() {
        val app = app { request ->
            when {
                request.url.encodedPath.endsWith("/product/detail") -> """{"data":{"id":"9","entityType":"product","title":"合成手机",
                    "configRows":[{"id":1,"title":"12GB+256GB","cpu":"合成芯片","ram":"12GB","price":3599,"url":"https://m.coolapk.com/mp/product/configInfo?id=1"},
                        {"id":2,"title":"16GB+1TB","cpu":"","ram":"","price":4599,"url":"https://m.coolapk.com/mp/product/configInfo?id=2"}],
                    "tabList":[{"title":"参数","page_name":"main","url":"/page?url=%2Fproduct%2FfeedList%3Ftype%3Dmain%26id%3D9","entityType":"page"}],"selectedTab":"main"}}"""
                request.url.encodedPath.endsWith("/page/dataList") && request.url.queryParameter("page") == "1" -> FixtureTransport.list(
                    """{"entityType":"card","entityTemplate":"productConfigList","title":"版本配置","subTitle":"配置对比",
                        "url":"https://m.coolapk.com/mp/productSelector/configCompare?productId=9","entities":[],"entityId":"productConfig"}""",
                    """{"entityType":"card","entityTemplate":"listCard","title":"同价位","url":"/page?url=x","entities":[
                        {"id":10,"entityType":"product","entityTemplate":"productSelect","title":"另一台合成手机","url":"/product/10"}]}""",
                )
                else -> FixtureTransport.list()
            }
        }
        compose.setContent { C001apkApp(app, Uri.parse("https://www.coolapk.com/product/9")) {} }
        waitText("12GB+256GB")
        // The memory already leads the title; the version without a chip takes the one all others share.
        assertEquals(2, compose.onAllNodesWithText("合成芯片").fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodesWithText("12GB", substring = true).fetchSemanticsNodes().size == 1)
        compose.onNodeWithText("¥4599").assertIsDisplayed()
        compose.onNodeWithText("配置对比").assertIsDisplayed()
        compose.onNodeWithText("同价位").assertIsDisplayed()
        compose.onNodeWithText("另一台合成手机").assertIsDisplayed()
    }

    @Test
    fun tradePostsShowThePriceAndOpenTheListingOutside() {
        val app = app { request ->
            when {
                request.url.encodedPath.endsWith("/product/detail") -> """{"data":{"id":"9","entityType":"product","title":"合成手机",
                    "tabList":[{"title":"交易","page_name":"trade","url":"/page?url=%2Fproduct%2FfeedList%3Ftype%3Dtrade%26id%3D9","entityType":"page"}],"selectedTab":"trade"}}"""
                request.url.encodedPath.endsWith("/page/dataList") && request.url.queryParameter("page") == "1" -> FixtureTransport.list(
                    """{"entityType":"feed","id":"500","uid":"10","username":"卖家","message":"出一台合成手机","dateline":1770000000,
                        "ershou_info":{"product_price":2200,"is_link":1,"link_url":"https://m.tb.cn/h.synthetic","link_source":"闲鱼","ershou_status":1,
                            "city":"合成市","product_config_data":"{\"configTitle\":\"12GB+256GB\"}"}}""",
                    """{"entityType":"feed","id":"501","uid":"11","username":"另一位卖家","message":"已经卖掉了","dateline":1770000000,
                        "ershou_info":{"product_price":1950,"is_link":1,"link_url":"https://m.tb.cn/h.sold","link_source":"闲鱼","ershou_status":-1}}""",
                )
                else -> FixtureTransport.list()
            }
        }
        compose.setContent { C001apkApp(app, Uri.parse("https://www.coolapk.com/product/9")) {} }
        waitText("¥2200")
        compose.onNodeWithText("交易完成").assertIsDisplayed()
        compose.onAllNodesWithText("交易完成").assertCountEquals(1)
        compose.onNodeWithText("12GB+256GB · 合成市 · 在闲鱼查看").assertIsDisplayed().performClick()
        val started = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity
        org.junit.Assert.assertEquals("https://m.tb.cn/h.synthetic", started?.dataString)
    }
}
