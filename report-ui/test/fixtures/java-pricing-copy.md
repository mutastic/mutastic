# Test to write: m855c8e3c

## No test checks `discount()` at `total == 100`

`src/main/java/com/example/pricing/Pricing.java:17` · m855c8e3c

```diff
- if (total >= 100) {
+ if (total > 100) {
```

**Why it got through:** Every test calls `discount()` away from the boundary. Every test that ran it still passed with the change in.

**Inputs the tests used:** `total`: `50`, `250` (none is `100`)

**Test to add** in `PricingTest`: Test `discount()` with `total = 100` and assert the result.

```java
@Test
void discountAtTheTotalEdge() {
  assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0
}
```

**Done when this passes:** `mutastic verify m855c8e3c java-pricing`
