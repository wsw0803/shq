package com.tencent.tim;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class TokenStoreTest {
    private static final String ACCESS = "TEST_ACCESS_0123456789ABCDEF";
    private static final String OPENID = "TEST_OPENID_0123456789ABCDEF";
    private static final String PAY = "TEST_PAY_0123456789ABCDEF";
    private static final String PARAMS = "access_token=" + ACCESS
            + "&expires_in=5184000&openid=" + OPENID + "&pay_token=" + PAY + "&";
    private static final String APPID = "1104466820";

    private static void assertFields(TokenStore.Entry e) {
        assertNotNull(e);
        assertEquals(ACCESS, e.accessToken);
        assertEquals(OPENID, e.openid);
        assertEquals(PAY, e.payToken);
        assertEquals("5184000", e.expiresIn);
    }

    @Test public void rawParametersRemainUnchanged() {
        TokenStore.Entry e = TokenStore.parseAnyInput(PARAMS);
        assertFields(e);
        assertEquals(PARAMS, e.raw);
    }

    @Test public void jsonMessageExtractsParametersAndAppid() throws Exception {
        String json = new JSONObject().put("msg", "其他游戏_client_id: " + APPID + ", " + PARAMS).toString();
        TokenStore.Entry e = TokenStore.parseAnyInput(json);
        assertFields(e);
        assertEquals(APPID, e.appid);
        assertEquals(PARAMS + "client_id=" + APPID, e.raw);
        assertEquals("", e.note);
    }

    @Test public void plainGameDescriptionIsRecognized() {
        TokenStore.Entry e = TokenStore.parseAnyInput("其他游戏_client_id: " + APPID + ", " + PARAMS);
        assertFields(e);
        assertEquals(APPID, e.appid);
        assertEquals(PARAMS + "client_id=" + APPID, e.raw);
    }

    @Test public void formattedJsonUnescapesMessageAndHandlesNoTrailingAmpersand() throws Exception {
        String msg = "其他游戏_client_id: " + APPID + ",\n" + PARAMS.substring(0, PARAMS.length() - 1);
        TokenStore.Entry e = TokenStore.parseAnyInput(new JSONObject().put("msg", msg).toString(2));
        assertFields(e);
        assertEquals(PARAMS + "client_id=" + APPID, e.raw);
    }

    @Test public void jsonWithoutGameDescriptionIsRecognized() throws Exception {
        TokenStore.Entry e = TokenStore.parseAnyInput(new JSONObject().put("msg", PARAMS).toString());
        assertFields(e);
        assertEquals(PARAMS, e.raw);
    }

    @Test public void existingClientIdIsNotDuplicated() throws Exception {
        String params = PARAMS + "client_id=" + APPID;
        TokenStore.Entry e = TokenStore.parseAnyInput(new JSONObject().put("msg", "其他游戏_client_id: " + APPID + ", " + params).toString());
        assertFields(e);
        assertEquals(params, e.raw);
    }

    @Test public void authLinkRemainsUnchanged() {
        String url = "auth://tauth.qq.com/?#" + PARAMS;
        assertFields(TokenStore.parseAnyInput(url));
        assertEquals(url, TokenStore.parseAnyInput(url).raw);
    }

    @Test public void savedLineRetainsMetadata() {
        TokenStore.Entry e = TokenStore.parseLine("测试备注 2026-11-13 12:34:56|" + APPID + "|" + PARAMS + "|capture", "测试游戏");
        assertFields(e);
        assertEquals(PARAMS, e.raw);
        assertEquals(APPID, e.appid);
        assertEquals("测试备注", e.note);
        assertEquals("2026-11-13 12:34:56", e.time);
        assertEquals("capture", e.mode);
        assertEquals("测试游戏", e.game);
    }

    @Test public void savedLineWithBraceInNoteRemainsSupported() {
        TokenStore.Entry e = TokenStore.parseLine("{备注} 2026-11-13 12:34:56|" + APPID + "|" + PARAMS + "|capture", "测试游戏");
        assertFields(e);
        assertEquals("{备注}", e.note);
        assertEquals(PARAMS, e.raw);
        assertEquals(APPID, e.appid);
    }

    @Test public void normalizedEntrySurvivesSaveAndReload() throws Exception {
        TokenStore.Entry e = TokenStore.parseAnyInput(new JSONObject().put("msg", "其他游戏_client_id: " + APPID + ", " + PARAMS).toString());
        TokenStore.Entry reloaded = TokenStore.parseLine(TokenStore.joinLine("备注", "2026-11-13 12:34:56", e.appid, e.raw, "capture"), "测试游戏");
        assertFields(reloaded);
        assertEquals(e.raw, reloaded.raw);
        assertEquals(APPID, reloaded.appid);
    }

    @Test public void bareTokenRemainsSupported() {
        assertEquals(ACCESS, TokenStore.parseAnyInput(ACCESS).accessToken);
    }

    @Test public void invalidJsonDoesNotBecomeAToken() {
        for (String input : new String[]{"", " ", "{}", "{broken", "{\"msg\":123}", "{\"msg\":\"登录失败\"}"}) {
            assertNull(input, TokenStore.parseAnyInput(input));
        }
        assertNull(TokenStore.parseAnyInput(null));
    }

    @Test public void clientIdSeparatorsAreDetectedAutomatically() throws Exception {
        for (String separator : new String[]{":", "/", "#", "=", " ", "：", " -> ", "\" : \"", "\n# "}) {
            String msg = "其他游戏_client_id" + separator + APPID + ", " + PARAMS;
            assertEquals(separator, APPID, TokenStore.appidOfUrl(msg));
            for (String input : new String[]{msg, new JSONObject().put("msg", msg).toString()}) {
                TokenStore.Entry e = TokenStore.parseAnyInput(input);
                assertFields(e);
                assertEquals(separator, APPID, e.appid);
                assertEquals(separator, PARAMS + "client_id=" + APPID, e.raw);
            }
        }
    }

    @Test public void generatedSeparatorsUseTheSameRule() throws Exception {
        java.util.Random random = new java.util.Random(42);
        for (int i = 0; i < 1000; i++) {
            StringBuilder separator = new StringBuilder();
            int length = 1 + random.nextInt(12);
            while (separator.length() < length) {
                char c = (char) (32 + random.nextInt(95));
                if (!Character.isLetterOrDigit(c)) separator.append(c);
            }
            String msg = "其他游戏_client_id" + separator + APPID + ", " + PARAMS;
            TokenStore.Entry e = TokenStore.parseAnyInput(new JSONObject().put("msg", msg).toString());
            assertFields(e);
            assertEquals(separator.toString(), APPID, e.appid);
            assertEquals(PARAMS + "client_id=" + APPID, e.raw);
        }
    }

    @Test public void parameterOrderIsPreserved() throws Exception {
        String params = "client_id=" + APPID + "&openid=" + OPENID
                + "&expires_in=5184000&access_token=" + ACCESS + "&pay_token=" + PAY + "&";
        for (String msg : new String[]{params, "其他游戏_client_id#" + APPID + ", " + params}) {
            for (String input : new String[]{msg, new JSONObject().put("msg", msg).toString()}) {
                TokenStore.Entry e = TokenStore.parseAnyInput(input);
                assertFields(e);
                assertEquals(params, e.raw);
                assertEquals(APPID, e.appid);
            }
        }
    }

    @Test public void jsonAuthLinkPreservesAllParameters() throws Exception {
        String url = "auth://tauth.qq.com/?#openid=" + OPENID + "&" + PARAMS;
        TokenStore.Entry e = TokenStore.parseAnyInput(new JSONObject().put("msg", url).toString());
        assertFields(e);
        assertEquals(url, e.raw);
    }

    @Test public void pipeSeparatorIsRecognizedWithoutJson() {
        TokenStore.Entry e = TokenStore.parseAnyInput("其他游戏_client_id|" + APPID + ", " + PARAMS);
        assertFields(e);
        assertEquals(PARAMS + "client_id=" + APPID, e.raw);
        assertEquals(APPID, e.appid);
    }

    @Test public void httpLinkWithClientIdRemainsUnchanged() {
        String url = "https://example.test/?client_id=" + APPID + "&" + PARAMS;
        TokenStore.Entry e = TokenStore.parseAnyInput(url);
        assertFields(e);
        assertEquals(url, e.raw);
    }

    @Test public void appidSupportsEqualsAndColon() {
        assertEquals(APPID, TokenStore.appidOfUrl("https://example.test/?client_id=" + APPID));
        assertEquals(APPID, TokenStore.appidOfUrl("其他游戏_client_id: " + APPID + ", "));
        assertEquals(APPID, TokenStore.appidOfUrl("client_id = " + APPID));
        assertEquals(APPID, TokenStore.appidOfUrl("pt_3rd_aid=" + APPID));
        assertNull(TokenStore.appidOfUrl("notclient_id=" + APPID));
        assertNull(TokenStore.appidOfUrl("client_id=123abc"));
    }

    // ---- 备注写坏的回归用例 ----

    @Test public void noteWithNewlineIsFlattenedOnSave() {
        TokenStore.Entry e = TokenStore.parseAnyInput(PARAMS);
        String line = TokenStore.joinLine("多行\n备注|带竖线", "2026-11-13 12:34:56",
                TokenStore.appidCol(e.appid, "", e.raw), e.raw, "capture");
        assertFalse("存档行不能含换行", line.contains("\n") || line.contains("\r"));
        assertEquals("存档行只能有 3 个分隔符", 3, countChar(line, '|'));
        TokenStore.Entry back = TokenStore.parseLine(line, "");
        assertFields(back);
        assertEquals(PARAMS, back.raw);
        assertEquals("备注里的换行和竖线被压成空格与斜杠", "多行 备注/带竖线", back.note);
    }

    @Test public void brokenMultiLineNoteDoesNotPoisonFollowingEntries() {
        String good1 = TokenStore.joinLine("", "2026-11-13 12:34:56", APPID, PARAMS, "capture");
        String good2 = TokenStore.joinLine("", "2026-11-13 13:00:00", APPID,
                "access_token=SECOND_" + ACCESS + "&openid=" + OPENID + "&", "login");
        String whole = "备注第一行" + (char) 10 + "第二行|" + good1 + (char) 10 + good2;
        TokenStore.Entry e = TokenStore.parseLine(whole, "");
        assertNotNull(e);
        assertFields(e);
        assertFalse("解析结果里不能再残留换行", e.raw.contains("\n"));
    }

    @Test public void cleanNoteStripsControlChars() {
        assertEquals("a b c", TokenStore.cleanNote("a\tb\nc"));
        assertEquals("a/b", TokenStore.cleanNote("a|b"));
        assertEquals("", TokenStore.cleanNote(null));
        assertEquals("  压缩 空格  ", " " + TokenStore.cleanNote(" 压缩   空格  ") + " ");
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
