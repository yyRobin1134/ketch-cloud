package com.linroid.ketch.cloud

import com.linroid.ketch.cloud.compat.util.Context
import com.linroid.ketch.cloud.compat.util.PlatformPrefs
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import kotlin.test.Test

/** One construction per test, so a crash on a target points at the part that causes it. */
class BisectTest {
  @Test fun a01Context() { Context.default }

  @Test fun a02Prefs() {
    val p = PlatformPrefs("bisect")
    p.write("k", "v")
    p.read("k")
  }

  @Test fun a03Database() { AppDatabase.get() }
  @Test fun a04Http() { HttpClients.apiClient() }
  @Test fun b01Quark() { QuarkApi() }
  @Test fun b02Uc() { UCApi() }
  @Test fun b03Xunlei() { XunleiApi() }
  @Test fun b04Baidu() { BaiduApi() }
  @Test fun b05C139() { C139Api() }
  @Test fun b06Pan123() { Pan123Api() }
  @Test fun b07Pan115() { Pan115Api() }
  @Test fun b08GuangYa() { GuangYaApi() }
  @Test fun b09ILanzou() { ILanzouApi() }
  @Test fun b10Lanzou() { LanzouApi() }
  @Test fun b11GitHub() { GitHubApi() }
}
