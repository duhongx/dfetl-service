package com.dfygt.dfetl.server.common;

import com.github.promeg.pinyinhelper.Pinyin;

/**
 * 中文/英文/数字混合串 → 拼音首字母小写码工具（spec 070 source_code 生成用）。
 *
 * <p>转换规则（见 spec 070 design.md §1）：
 * <ul>
 *     <li>中文字符 → 该字拼音首字母（小写）。多音字取拼音库（TinyPinyin）默认读音，已知限制。</li>
 *     <li>英文字母 → 原样小写。</li>
 *     <li>数字（0-9） → 原样保留。</li>
 *     <li>其它字符（空格、括号、标点、控制字符等） → 跳过。</li>
 *     <li>输入为 {@code null}、空串，或所有字符跳过后结果为空 → 返回占位 {@code "x"}（唯一性靠序号兜底）。</li>
 * </ul>
 *
 * <p>示例：
 * <pre>
 *   toInitials("县人民医院")        → "xrmyy"
 *   toInitials("HIS业务")           → "hisyw"
 *   toInitials("123 中心 (急诊)")   → "123zxjz"
 *   toInitials(null)                 → "x"
 *   toInitials("")                   → "x"
 *   toInitials("!!!")                → "x"
 * </pre>
 *
 * <p>多音字限制：TinyPinyin 取词典默认读音，对人名/地名等多音字可能判错（如"重庆" zhòng/chóng）。
 * 业务侧采用「生成即存死」+「序号兜底唯一」的策略消化此误差，不引入人工校正。
 *
 * <p>纯静态工具类，禁止实例化。
 */
public final class PinyinInitialUtil {

    private PinyinInitialUtil() {}

    /**
     * 把混合字符串转换为小写拼音首字母码。规则见类级 javadoc。
     *
     * @param text 任意输入字符串（可空）
     * @return 仅含 {@code [a-z0-9]} 的小写串；输入为空或转换后为空时返回 {@code "x"}
     */
    public static String toInitials(String text) {
        if (text == null || text.isEmpty()) {
            return "x";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Pinyin.isChinese(c)) {
                String py = Pinyin.toPinyin(c);
                if (py != null && !py.isEmpty()) {
                    sb.append(Character.toLowerCase(py.charAt(0)));
                }
            } else if (c >= 'A' && c <= 'Z') {
                sb.append((char) (c + 32));
            } else if (c >= 'a' && c <= 'z') {
                sb.append(c);
            } else if (c >= '0' && c <= '9') {
                sb.append(c);
            }
            // 其它字符（空格/标点/控制字符/未识别 Unicode）一律跳过
        }
        return sb.length() == 0 ? "x" : sb.toString();
    }
}
