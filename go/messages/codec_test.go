package main

import "testing"

func TestCodec(t *testing.T) {
	if err := codecSelfTest(); err != nil {
		t.Fatal(err)
	}
}
