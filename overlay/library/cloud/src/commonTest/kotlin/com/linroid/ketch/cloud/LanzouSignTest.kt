package com.linroid.ketch.cloud

import com.yunx.app.data.network.LanzouApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanzouSignTest {
  private val page = """
    <script>
    var kdns =1;
    var isngis = '';
    var isngis = 'VTMFOw08AjMDClNsUGA_abc';
    //data : { 'action':'downprocess','sign':oldname,'kd':kdns,'p':pwd, },
    ${'$'}.ajax({
      url : 'https://apifile.woozooo.com/ajaxfile.php?file=327064083',
      data : { 'action':'downprocess','sign':isngis,'kd':kdns,'p':pwd, },
    });
    </script>
  """.trimIndent()

  @Test
  fun readsTheVariableTheRequestUses() {
    assertEquals("VTMFOw08AjMDClNsUGA_abc", LanzouApi().signFromPage(page))
  }

  @Test
  fun noRequestMeansNoSign() {
    assertNull(LanzouApi().signFromPage("<html>nothing here</html>"))
  }
}
