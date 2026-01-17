# EntityView Framework

A lightweight framework for exposing JPA entities to LLMs with automatic tool generation, transactional safety, and sensible defaults.

## Quick Start

Create a view interface for your entity:

```java
@LlmView
public interface CustomerView extends EntityView<Customer> {

    @LlmTool(description = "Get customer's reservations")
    default List<Reservation> getReservations() {
        return getEntity().getReservations();
    }
}
```

That's it. The framework:
- Auto-discovers your view at startup
- Generates a default summary: `Customer (id=123)`
- Generates default fullText with all properties
- Creates tools from `@LlmTool` methods
- Handles transactions and lazy loading
- **EntityView IS an LlmReference** - add directly to conversations

## Usage

### Wire up in your agent

```java
// Create a view - it's already an LlmReference with content + tools
var customerView = entityViewService.viewOf(customer);

// Add directly to conversation (provides both prompt content and tools)
conversation.withReference(customerView);

// Or add finder tools to look up entities by ID
conversation.withTools(entityViewService.findersFor(Reservation.class, Flight.class));
```

### Customize summary and fullText

Override when you need custom formatting:

```java
@LlmView
public interface CustomerView extends EntityView<Customer> {

    @Override
    default String summary() {
        return "Customer: " + getEntity().getName() + " (" + getEntity().getStatus() + ")";
    }

    @Override
    default String fullText() {
        var c = getEntity();
        return """
            Customer: %s
            Email: %s
            Status: %s
            Reservations: %d
            """.formatted(c.getName(), c.getEmail(), c.getStatus(), c.getReservations().size());
    }
}
```

### Add tools

Annotate methods with `@LlmTool`:

```java
@LlmTool(description = "Check in for all flights")
default String checkIn() {
    getEntity().setCheckedIn(true);
    return "Successfully checked in";
}
```

### Filtered Relationship Methods (Recommended)

**This is a powerful pattern.** Instead of exposing raw collections, add filtering parameters that let the LLM request exactly what it needs:

```java
@LlmTool(description = "Get reservations, optionally filtered by date range")
default List<Reservation> getReservations(
        @LlmTool.Param(description = "Start date (YYYY-MM-DD)", required = false) LocalDate from,
        @LlmTool.Param(description = "End date (YYYY-MM-DD)", required = false) LocalDate to
) {
    return getEntity().getReservations().stream()
            .filter(r -> from == null || !r.getDate().isBefore(from))
            .filter(r -> to == null || !r.getDate().isAfter(to))
            .toList();
}
```

**Why this matters:**

| User asks | LLM calls | Result |
|-----------|-----------|--------|
| "Show my reservations" | `getReservations()` | All reservations |
| "Any flights next week?" | `getReservations(from: "2026-01-20", to: "2026-01-27")` | Just next week |
| "My upcoming flights" | `getReservations(from: "2026-01-17")` | Future only |

Without filtering, the LLM would retrieve everything and try to filter in its response - wasting tokens and risking errors. With filtering, it gets precisely what it needs.

**Common filter patterns:**

```java
// Date range filtering
@LlmTool(description = "Get orders, optionally filtered by status and date")
default List<Order> getOrders(
        @LlmTool.Param(description = "Filter by status", required = false) OrderStatus status,
        @LlmTool.Param(description = "Only orders after this date", required = false) LocalDate since
) { ... }

// Search/text filtering
@LlmTool(description = "Search products by name")
default List<Product> searchProducts(
        @LlmTool.Param(description = "Search term") String query,
        @LlmTool.Param(description = "Max results", required = false) Integer limit
) { ... }

// Boolean flags
@LlmTool(description = "Get tasks, optionally only incomplete ones")
default List<Task> getTasks(
        @LlmTool.Param(description = "Only show incomplete tasks", required = false) Boolean incompleteOnly
) { ... }
```

Make filter parameters `required = false` so the LLM can omit them for "get all" queries.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        Your Code                            │
├─────────────────────────────────────────────────────────────┤
│  @LlmView                                                   │
│  interface CustomerView extends EntityView<Customer> {      │
│      @LlmTool default List<Reservation> getReservations()   │
│      default String summary() { ... }  // optional          │
│  }                                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    EntityViewService                        │
├─────────────────────────────────────────────────────────────┤
│  • Auto-discovers @LlmView interfaces at startup      │
│  • Creates dynamic proxies for views                        │
│  • Generates tools from @LlmTool methods                    │
│  • Handles transactions (reloads entity per invocation)     │
│  • Falls back to reflection-based defaults                  │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                         LLM                                 │
├─────────────────────────────────────────────────────────────┤
│  Sees:                                                      │
│  • Entity summary in context                                │
│  • Tools: getReservations, checkIn, find_reservation, etc.  │
└─────────────────────────────────────────────────────────────┘
```

## Summary/FullText Resolution

The framework uses a fallback chain:

```
┌──────────────────┐
│ Jinja Template   │  prompts/views/customer_short.jinja
│ (if exists)      │  prompts/views/customer_long.jinja
└────────┬─────────┘
         │ not found
         ▼
┌──────────────────┐
│ Your Override    │  default String summary() { ... }
│ (if implemented) │  default String fullText() { ... }
└────────┬─────────┘
         │ not implemented
         ▼
┌──────────────────┐
│ Reflection       │  summary: "Customer (id=123)"
│ Strategy         │  fullText: all properties + collection sizes
└──────────────────┘
```

### Default Output Examples

**summary()** - Just type and ID:
```
Customer (id=123)
```

**fullText()** - Simple properties + collection sizes:
```
Customer
  id: 123
  name: John Doe
  email: john@example.com
  status: GOLD
  reservations: 5 items
```

## Finder Tools

Create finder tools to let the LLM look up entities:

```java
// Single finder
conversation.withTool(entityViewService.finderFor(Reservation.class));

// Multiple finders
conversation.withTools(entityViewService.findersFor(Reservation.class, Flight.class));
```

This creates a `find_reservation` tool that:
1. Accepts an entity ID
2. Returns the entity's fullText
3. Exposes that entity's `@LlmTool` methods for follow-up calls

```
┌─────────────────────────────────────────┐
│           find_reservation              │
│  "Find a reservation by ID"             │
├─────────────────────────────────────────┤
│  Input: { "id": "abc-123" }             │
│  Output: Reservation details + tools    │
│                                         │
│  Unlocks: checkIn, cancel, etc.         │
└─────────────────────────────────────────┘
```

## Why Interfaces?

You might wonder why views are interfaces rather than annotated entities:

1. **Separation of concerns** - AI presentation stays out of domain model
2. **Transactional safety** - Framework reloads entity within transactions
3. **Proxy simplicity** - JDK dynamic proxies work easily with interfaces
4. **No Hibernate conflicts** - Doesn't interfere with JPA's own proxying

The `getEntity().` prefix in your methods is the mechanism that enables safe, transactional access to lazy-loaded collections.

## Complete Example

```java
@LlmView
public interface CustomerView extends EntityView<Customer> {

    // Tools exposed to LLM

    @LlmTool(description = "Get flight reservations, optionally filtered by date")
    default List<Reservation> getReservations(
            @LlmTool.Param(description = "Start date", required = false) LocalDate from,
            @LlmTool.Param(description = "End date", required = false) LocalDate to
    ) {
        return getEntity().getReservations().stream()
                .filter(r -> from == null || !r.getDate().isBefore(from))
                .filter(r -> to == null || !r.getDate().isAfter(to))
                .toList();
    }

    @LlmTool(description = "Get loyalty program status")
    default LoyaltyStatus getStatus() {
        return getEntity().getLoyaltyStatus();
    }

    // Custom formatting (optional - defaults work fine for simple cases)

    @Override
    default String summary() {
        var c = getEntity();
        return "Customer: %s (%s)".formatted(c.getName(), c.getLoyaltyStatus().getLevel());
    }

    @Override
    default String fullText() {
        var c = getEntity();
        return """
            Customer: %s
            Email: %s
            Member ID: %s
            Status: %s (%,d points)
            Reservations: %d
            """.formatted(
                c.getName(),
                c.getEmail(),
                c.getLoyaltyStatus().getMemberId(),
                c.getLoyaltyStatus().getLevel(),
                c.getLoyaltyStatus().getPoints(),
                c.getReservations().size()
            );
    }
}
```

Usage:
```java
@Component
public class ChatActions {

    private final EntityViewService entityViewService;

    public Conversation createConversation(Customer customer) {
        return Conversation.builder()
                .withReference(entityViewService.viewOf(customer))
                .withTools(entityViewService.findersFor(Reservation.class))
                .build();
    }
}
```
