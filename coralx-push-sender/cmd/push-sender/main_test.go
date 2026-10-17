package main

import (
	"slices"
	"strings"
	"testing"
)

// The ordering trap that cost a working deployment one silent failure.
//
// Subscribe sends these as one `event json ...` line, and FreeSWITCH reads
// every token after CUSTOM as a custom subclass. A standard event placed after
// the subclasses is accepted with +OK and then never delivered, so the only
// symptom is the feature quietly not working. CHANNEL_OUTGOING must sit with
// the standard events.
func TestEventsForKeepsStandardEventsBeforeCustom(t *testing.T) {
	events := eventsFor(config{wakeOnOutboundLeg: true})

	custom := slices.Index(events, "CUSTOM")
	outgoing := slices.Index(events, "CHANNEL_OUTGOING")
	if custom < 0 {
		t.Fatalf("CUSTOM must be subscribed: %v", events)
	}
	if outgoing < 0 {
		t.Fatalf("CHANNEL_OUTGOING must be subscribed when the flag is set: %v", events)
	}
	if outgoing > custom {
		t.Fatalf("CHANNEL_OUTGOING after CUSTOM is read as a subclass and never arrives: %v", events)
	}
	for _, standard := range standardEvents {
		if i := slices.Index(events, standard); i > custom {
			t.Fatalf("%s must precede CUSTOM: %v", standard, events)
		}
	}
}

// Every subclass has to stay inside CUSTOM's scope, i.e. after it.
func TestEventsForKeepsSubclassesAfterCustom(t *testing.T) {
	for _, flag := range []bool{false, true} {
		events := eventsFor(config{wakeOnOutboundLeg: flag})
		custom := slices.Index(events, "CUSTOM")
		for _, name := range events {
			if !strings.Contains(name, "::") {
				continue
			}
			if slices.Index(events, name) < custom {
				t.Fatalf("subclass %s before CUSTOM is not scoped by it: %v", name, events)
			}
		}
	}
}

// The default deployment is the one whose dialplan fires push_wake itself, and
// it must not pay for an event it has no use for.
func TestEventsForOmitsOutgoingUnlessAsked(t *testing.T) {
	events := eventsFor(config{wakeOnOutboundLeg: false})

	if slices.Contains(events, "CHANNEL_OUTGOING") {
		t.Fatalf("CHANNEL_OUTGOING must not be subscribed by default: %v", events)
	}
	if !slices.Contains(events, "coralx::push_wake") {
		t.Fatalf("the dialplan announcement must still be subscribed: %v", events)
	}
}

// eventsFor must not scribble on the package-level slices it builds from: two
// calls with different flags have to give two independent answers.
func TestEventsForDoesNotAliasItsSources(t *testing.T) {
	before := append([]string{}, standardEvents...)

	withFlag := eventsFor(config{wakeOnOutboundLeg: true})
	without := eventsFor(config{wakeOnOutboundLeg: false})

	if !slices.Equal(standardEvents, before) {
		t.Fatalf("standardEvents was mutated: %v", standardEvents)
	}
	if slices.Contains(without, "CHANNEL_OUTGOING") {
		t.Fatalf("the second call inherited the first's event: %v", without)
	}
	if !slices.Contains(withFlag, "CHANNEL_OUTGOING") {
		t.Fatalf("the first call lost its event: %v", withFlag)
	}
}
