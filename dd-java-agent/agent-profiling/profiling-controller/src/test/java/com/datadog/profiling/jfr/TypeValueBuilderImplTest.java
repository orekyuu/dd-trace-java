package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Array;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TypeValueBuilderImplTest {
  private static final String CUSTOM_FIELD_NAME = "custom_field";
  private static final String CUSTOM_FIELD_ARRAY_NAME = "custom_field_arr";
  private static final String SIMPLE_FIELD_VALUE = "hello";
  private static final String SIMPLE_FIELD_NAME = "field";
  private static Map<Types.Builtin, String> typeToFieldMap;
  private TypeValueBuilderImpl instance;
  private Type simpleType;
  private Type customType;
  private Type stringType;

  @BeforeAll
  static void init() {
    typeToFieldMap = new HashMap<>(Types.Builtin.values().length);
    for (Types.Builtin builtin : Types.Builtin.values()) {
      typeToFieldMap.put(builtin, builtin.name().toLowerCase() + "_field");
    }
  }

  @BeforeEach
  void setUp() {
    // not mocking here since we will need quite a number of predefined types anyway
    Types types = new Types(new Metadata(new ConstantPools()));

    stringType = types.getType(Types.Builtin.STRING);

    simpleType =
        types.getOrAdd(
            "custom.Simple",
            builder -> {
              builder.addField(SIMPLE_FIELD_NAME, Types.Builtin.STRING);
            });

    customType =
        types.getOrAdd(
            "custom.Type",
            builder -> {
              for (Types.Builtin builtin : Types.Builtin.values()) {
                builder
                    .addField(getFieldName(builtin), builtin)
                    .addField(getArrayFieldName(builtin), builtin, TypedFieldBuilder::asArray);
              }
              builder
                  .addField(CUSTOM_FIELD_NAME, simpleType)
                  .addField(CUSTOM_FIELD_ARRAY_NAME, simpleType, TypedFieldBuilder::asArray);
            });

    instance = new TypeValueBuilderImpl(customType);
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void putFieldBuiltin(Types.Builtin target) {
    for (Types.Builtin type : Types.Builtin.values()) {
      if (type == target) {
        assertCorrectFieldValueBuiltinType(target, type, 1, false);
      } else {
        assertWrongFieldValueBuiltinType(target, type, 0);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void putFieldBuiltinArray(Types.Builtin target) {
    for (Types.Builtin type : Types.Builtin.values()) {
      if (type == target) {
        assertCorrectFieldValueBuiltinType(target, type, 1, true);
      } else {
        assertWrongFieldValueBuiltinType(target, type, 0);
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void putFieldBuiltinArrayNonExistent(Types.Builtin target) {
    assertThrows(
        IllegalArgumentException.class,
        () -> testPutBuiltinFieldArray(target, "not a field name", 1));
  }

  @Test
  void putFieldCustom() {
    instance.putField(CUSTOM_FIELD_NAME, SIMPLE_FIELD_VALUE);

    TypedFieldValue fieldValue = instance.build().get(CUSTOM_FIELD_NAME);
    assertNotNull(fieldValue);
    assertEquals(CUSTOM_FIELD_NAME, fieldValue.getField().getName());
    assertEquals(SIMPLE_FIELD_VALUE, fieldValue.getValue().getValue());
  }

  @Test
  void putFieldCustomBuilder() {
    instance.putField(
        CUSTOM_FIELD_NAME,
        v -> {
          v.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
        });

    TypedFieldValue fieldValue = instance.build().get(CUSTOM_FIELD_NAME);
    assertNotNull(fieldValue);
    assertEquals(CUSTOM_FIELD_NAME, fieldValue.getField().getName());
    assertEquals(SIMPLE_FIELD_VALUE, fieldValue.getValue().getValue());
  }

  @Test
  void putFieldCustomArray() {
    instance.putField(
        CUSTOM_FIELD_ARRAY_NAME,
        simpleType.asValue(v -> v.putField(SIMPLE_FIELD_NAME, "value1")),
        simpleType.asValue(v -> v.putField(SIMPLE_FIELD_NAME, "value2")));

    TypedFieldValue fieldValue = instance.build().get(CUSTOM_FIELD_ARRAY_NAME);
    assertNotNull(fieldValue);
    assertEquals(CUSTOM_FIELD_ARRAY_NAME, fieldValue.getField().getName());
  }

  @Test
  void putFieldCustomArrayNonArrayField() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            instance.putField(
                CUSTOM_FIELD_NAME,
                simpleType.asValue(v -> v.putField(SIMPLE_FIELD_NAME, "value1")),
                simpleType.asValue(v -> v.putField(SIMPLE_FIELD_NAME, "value2"))));
  }

  @Test
  void putFieldCustomArrayNonExistingField() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            instance.putField(
                "not a field name",
                simpleType.asValue(v -> v.putField(SIMPLE_FIELD_NAME, "value1")),
                simpleType.asValue(v -> v.putField(SIMPLE_FIELD_NAME, "value2"))));
  }

  @Test
  void putFieldCustomArrayInvalidValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            instance.putField(
                CUSTOM_FIELD_ARRAY_NAME,
                stringType.asValue("value1"),
                stringType.asValue("value2")));
  }

  @Test
  void putFieldCustomInvalid() {
    assertThrows(IllegalArgumentException.class, () -> instance.putField(CUSTOM_FIELD_NAME, 0L));
  }

  @Test
  public void putFieldCustomBuilderArray() {
    TypedFieldValue value =
        instance
            .putField(
                CUSTOM_FIELD_ARRAY_NAME,
                fld1 -> {
                  fld1.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                },
                fld2 -> {
                  fld2.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                })
            .build()
            .get(CUSTOM_FIELD_ARRAY_NAME);

    assertEquals(CUSTOM_FIELD_ARRAY_NAME, value.getField().getName());
    assertNotNull(value);
  }

  @Test
  public void putFieldCustomBuilderArrayNonArrayField() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          instance
              .putField(
                  CUSTOM_FIELD_NAME,
                  fld1 -> {
                    fld1.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                  },
                  fld2 -> {
                    fld2.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                  })
              .build()
              .get(CUSTOM_FIELD_ARRAY_NAME);
        });
  }

  @Test
  public void putFieldCustomBuilderArrayNonExistingField() {
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          instance
              .putField(
                  "not a field name",
                  fld1 -> {
                    fld1.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                  },
                  fld2 -> {
                    fld2.putField(SIMPLE_FIELD_NAME, SIMPLE_FIELD_VALUE);
                  })
              .build()
              .get(CUSTOM_FIELD_ARRAY_NAME);
        });
  }

  private void assertCorrectFieldValueBuiltinType(
      Types.Builtin target, Types.Builtin type, int value, boolean asArray) {
    if (asArray) {
      testPutBuiltinFieldArray(target, getArrayFieldName(type), value);
    } else {
      testPutBuiltinField(target, getFieldName(type), value);
    }

    String fieldName = asArray ? getArrayFieldName(type) : getFieldName(type);
    TypedFieldValue fieldValue = instance.build().get(fieldName);
    assertNotNull(fieldValue);
    assertEquals(fieldName, fieldValue.getField().getName());

    Object targetValue = null;
    if (asArray) {
      Object targetValues = fieldValue.getValues();
      assertNotNull(targetValues);
      assertTrue(targetValues.getClass().isArray());
      targetValue = Array.get(targetValues, 0);
    } else {
      targetValue = fieldValue.getValue().getValue();
    }
    assertNotNull(targetValue);
    if (targetValue instanceof Number) {
      assertEquals(value, ((Number) targetValue).intValue());
    } else if (targetValue instanceof String) {
      assertEquals(String.valueOf(value), targetValue);
    } else if (targetValue instanceof Boolean) {
      assertEquals(value > 0, targetValue);
    }
  }

  private void assertWrongFieldValueBuiltinType(
      Types.Builtin target, Types.Builtin type, int value) {
    assertThrows(
        IllegalArgumentException.class,
        () -> testPutBuiltinField(target, getArrayFieldName(type), value));
    assertThrows(
        IllegalArgumentException.class,
        () -> testPutBuiltinFieldArray(target, getFieldName(type), value));
  }

  private void testPutBuiltinField(Types.Builtin target, String fieldName, int value) {
    switch (target) {
      case BYTE:
        {
          instance.putField(fieldName, (byte) value);
          break;
        }
      case CHAR:
        {
          instance.putField(fieldName, (char) value);
          break;
        }
      case SHORT:
        {
          instance.putField(fieldName, (short) value);
          break;
        }
      case INT:
        {
          instance.putField(fieldName, (int) value);
          break;
        }
      case LONG:
        {
          instance.putField(fieldName, (long) value);
          break;
        }
      case FLOAT:
        {
          instance.putField(fieldName, (float) value);
          break;
        }
      case DOUBLE:
        {
          instance.putField(fieldName, (double) value);
          break;
        }
      case BOOLEAN:
        {
          instance.putField(fieldName, (int) (value) > 0);
          break;
        }
      case STRING:
        {
          instance.putField(fieldName, String.valueOf(value));
          break;
        }
    }
  }

  private void testPutBuiltinFieldArray(Types.Builtin target, String fieldName, int value) {
    switch (target) {
      case BYTE:
        {
          instance.putField(fieldName, new byte[] {(byte) value});
          break;
        }
      case CHAR:
        {
          instance.putField(fieldName, new char[] {(char) value});
          break;
        }
      case SHORT:
        {
          instance.putField(fieldName, new short[] {(short) value});
          break;
        }
      case INT:
        {
          instance.putField(fieldName, new int[] {(int) value});
          break;
        }
      case LONG:
        {
          instance.putField(fieldName, new long[] {(long) value});
          break;
        }
      case FLOAT:
        {
          instance.putField(fieldName, new float[] {(float) value});
          break;
        }
      case DOUBLE:
        {
          instance.putField(fieldName, new double[] {(double) value});
          break;
        }
      case BOOLEAN:
        {
          instance.putField(fieldName, new boolean[] {(int) (value) > 0});
          break;
        }
      case STRING:
        {
          instance.putField(fieldName, new String[] {String.valueOf(value)});
          break;
        }
    }
  }

  private static String getFieldName(Types.Builtin type) {
    return typeToFieldMap.get(type);
  }

  private static String getArrayFieldName(Types.Builtin type) {
    return getFieldName(type) + "_arr";
  }
}