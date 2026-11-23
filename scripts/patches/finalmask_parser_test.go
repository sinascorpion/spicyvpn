package conf

import (
    "encoding/json"
    "testing"
)

func TestSpicyFinalMaskSequenceAndLegacy(t *testing.T) {
    for _, input := range []string{
        `{"packets":"tlshello","lengths":["0","104","1"],"delays":["0"],"maxSplit":"0"}`,
        `{"packets":"1-1","lengths":["114","1"],"delays":["1"],"maxSplit":"11"}`,
        `{"packets":"tlshello","length":"10-20","delay":"0-1"}`,
    } {
        var mask FragmentMask
        if err := json.Unmarshal([]byte(input), &mask); err != nil { t.Fatal(err) }
        if _, err := mask.Build(); err != nil { t.Fatalf("%s: %v", input, err) }
    }
}

func TestSpicyFinalMaskRejectsNonProgressingSequence(t *testing.T) {
    var mask FragmentMask
    if err := json.Unmarshal([]byte(`{"packets":"tlshello","lengths":["104","0"]}`), &mask); err != nil { t.Fatal(err) }
    if _, err := mask.Build(); err == nil { t.Fatal("terminal zero length must be rejected") }
}
