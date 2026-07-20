package com.cretas.aims.service.supplier;

import com.cretas.aims.exception.BusinessException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared normalization and validation contract for manual and Excel supplier writes. */
public final class SupplierProfileValidator {
    public static final String PHONE_REGEXP =
            "^(?:(?:\\+?86[- ]?)?1[3-9]\\d{9}|(?:\\+?86[- ]?)?0\\d{2,3}[- ]?\\d{7,8}(?:[- ]?(?:\\d{1,6}|(?:ext\\.?|分机|转)\\s*\\d{1,6}))?)$";
    public static final String READABLE_ADDRESS_REGEXP = "^(?=.*[\\p{L}\\p{N}]).+$";

    private static final Pattern PHONE = Pattern.compile(PHONE_REGEXP, Pattern.CASE_INSENSITIVE);
    private static final Pattern READABLE_ADDRESS = Pattern.compile(READABLE_ADDRESS_REGEXP);

    private SupplierProfileValidator() {}

    public static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static Map<String, String> validate(String name, String contactPerson,
                                                String phone, String address) {
        Map<String, String> errors = new LinkedHashMap<>();
        String normalizedName = trimToNull(name);
        String normalizedContact = trimToNull(contactPerson);
        String normalizedPhone = trimToNull(phone);
        String normalizedAddress = trimToNull(address);
        if (normalizedName == null) errors.put("name", "供应商名称不能为空");
        if (normalizedContact == null) errors.put("contactPerson", "联系人不能为空");
        if (normalizedPhone == null) {
            errors.put("phone", "联系电话不能为空");
        } else if (!PHONE.matcher(normalizedPhone).matches()) {
            errors.put("phone", "联系电话格式不正确，请填写中国大陆手机号或带区号的固定电话（可含分机）");
        }
        if (normalizedAddress == null) {
            errors.put("address", "地址不能为空");
        } else if (!READABLE_ADDRESS.matcher(normalizedAddress).matches()) {
            errors.put("address", "地址必须包含可识别的文字或数字，不能仅填写符号");
        }
        return errors;
    }

    public static void validateOrThrow(String name, String contactPerson, String phone, String address) {
        Map<String, String> errors = validate(name, contactPerson, phone, address);
        if (!errors.isEmpty()) {
            Map.Entry<String, String> first = errors.entrySet().iterator().next();
            throw new BusinessException(400, first.getValue())
                    .withHint("请补全并检查供应商名称、联系人、联系电话和地址")
                    .withHintTarget(first.getKey());
        }
    }

    public static boolean isComplete(String name, String contactPerson, String phone, String address) {
        return validate(name, contactPerson, phone, address).isEmpty();
    }
}
