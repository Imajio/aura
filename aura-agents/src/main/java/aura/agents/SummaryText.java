package aura.agents;

/**
 * Сжимает текст до размера подсказки для нарратора.
 *
 * <p>Живёт отдельно, потому что нужен обоим адаптерам: держать по копии в каждом
 * значило бы дословно продублировать логический блок.
 */
final class SummaryText {

    private static final int MAX_LENGTH = 200;

    private SummaryText() {
    }

    /** Сплющивает переводы строк и режет до {@value #MAX_LENGTH} символов. */
    static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= MAX_LENGTH ? flat : flat.substring(0, MAX_LENGTH) + "…";
    }
}
