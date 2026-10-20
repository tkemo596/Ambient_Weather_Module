package com.arringtonautomation.ignition.ambientweather.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free JSON parser. The Ambient Weather payloads are simple (objects, arrays,
 * strings, numbers, booleans), and keeping the API layer free of Ignition's shaded Gson lets it be
 * compiled and unit-tested without the SDK on the classpath.
 *
 * <p>Objects become {@link LinkedHashMap}, arrays {@link ArrayList}, integers {@link Long},
 * other numbers {@link Double}, plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class MiniJson {

  private final String s;
  private int i;

  private MiniJson(String s) {
    this.s = s;
  }

  public static Object parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("JSON input is null");
    }
    MiniJson p = new MiniJson(json);
    p.ws();
    Object v = p.value();
    p.ws();
    if (p.i != p.s.length()) {
      throw p.error("Unexpected trailing content");
    }
    return v;
  }

  private Object value() {
    if (i >= s.length()) {
      throw error("Unexpected end of input");
    }
    char c = s.charAt(i);
    switch (c) {
      case '{':
        return object();
      case '[':
        return array();
      case '"':
        return string();
      case 't':
        literal("true");
        return Boolean.TRUE;
      case 'f':
        literal("false");
        return Boolean.FALSE;
      case 'n':
        literal("null");
        return null;
      default:
        if (c == '-' || (c >= '0' && c <= '9')) {
          return number();
        }
        throw error("Unexpected character '" + c + "'");
    }
  }

  private Map<String, Object> object() {
    Map<String, Object> m = new LinkedHashMap<>();
    i++; // {
    ws();
    if (peek() == '}') {
      i++;
      return m;
    }
    while (true) {
      ws();
      if (peek() != '"') {
        throw error("Expected object key");
      }
      String key = string();
      ws();
      expect(':');
      ws();
      m.put(key, value());
      ws();
      char c = next();
      if (c == '}') {
        return m;
      }
      if (c != ',') {
        throw error("Expected ',' or '}'");
      }
    }
  }

  private List<Object> array() {
    List<Object> l = new ArrayList<>();
    i++; // [
    ws();
    if (peek() == ']') {
      i++;
      return l;
    }
    while (true) {
      ws();
      l.add(value());
      ws();
      char c = next();
      if (c == ']') {
        return l;
      }
      if (c != ',') {
        throw error("Expected ',' or ']'");
      }
    }
  }

  private String string() {
    i++; // opening quote
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (i >= s.length()) {
        throw error("Unterminated string");
      }
      char c = s.charAt(i++);
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      if (i >= s.length()) {
        throw error("Unterminated escape");
      }
      char e = s.charAt(i++);
      switch (e) {
        case '"': sb.append('"'); break;
        case '\\': sb.append('\\'); break;
        case '/': sb.append('/'); break;
        case 'b': sb.append('\b'); break;
        case 'f': sb.append('\f'); break;
        case 'n': sb.append('\n'); break;
        case 'r': sb.append('\r'); break;
        case 't': sb.append('\t'); break;
        case 'u':
          if (i + 4 > s.length()) {
            throw error("Bad unicode escape");
          }
          try {
            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
          } catch (NumberFormatException ex) {
            throw error("Bad unicode escape");
          }
          i += 4;
          break;
        default:
          throw error("Bad escape '\\" + e + "'");
      }
    }
  }

  private Object number() {
    int start = i;
    boolean integral = true;
    if (peek() == '-') {
      i++;
    }
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') {
        i++;
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        integral = false;
        i++;
      } else {
        break;
      }
    }
    String n = s.substring(start, i);
    try {
      if (integral) {
        try {
          return Long.parseLong(n);
        } catch (NumberFormatException overflow) {
          return Double.parseDouble(n);
        }
      }
      return Double.parseDouble(n);
    } catch (NumberFormatException ex) {
      throw error("Bad number '" + n + "'");
    }
  }

  private void literal(String word) {
    if (!s.startsWith(word, i)) {
      throw error("Expected '" + word + "'");
    }
    i += word.length();
  }

  private void ws() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }

  private char peek() {
    if (i >= s.length()) {
      throw error("Unexpected end of input");
    }
    return s.charAt(i);
  }

  private char next() {
    char c = peek();
    i++;
    return c;
  }

  private void expect(char c) {
    if (next() != c) {
      throw error("Expected '" + c + "'");
    }
  }

  private IllegalArgumentException error(String msg) {
    return new IllegalArgumentException(msg + " at position " + i);
  }
}
