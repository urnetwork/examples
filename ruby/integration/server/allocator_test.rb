# frozen_string_literal: true

# Tests that allocator.rb runs its command line only as a program: the embed
# backend tool (../../embed/server/backend.rb) requires it as a module, and a
# require must neither run a command nor exit. Each case runs in a child
# Ruby, so a regression cannot end this process.
#
# Run from this directory: ruby allocator_test.rb

require "minitest/autorun"
require "open3"
require "rbconfig"
require "tmpdir"

class AllocatorRequireTest < Minitest::Test
  ALLOCATOR = File.expand_path("allocator.rb", __dir__)
  RUBY = RbConfig.ruby

  def test_require_runs_no_command
    # ARGV names a command that the program would run and print
    script = "ARGV.replace(['--self-test']); require #{ALLOCATOR.dump}; puts Allocator.respond_to?(:allocate)"
    out, err, status = Open3.capture3(RUBY, "-e", script)
    assert status.success?, err
    assert_equal "true\n", out
    assert_empty err
  end

  def test_require_with_invalid_arguments_does_not_exit
    # as a program these arguments fail with exit 1
    script = "ARGV.replace(['not-a-key']); require #{ALLOCATOR.dump}; puts 'still running'"
    out, err, status = Open3.capture3(RUBY, "-e", script)
    assert status.success?, err
    assert_equal "still running\n", out
  end

  def test_runs_as_a_program_from_any_directory
    Dir.chdir(Dir.tmpdir) do
      out, err, status = Open3.capture3(RUBY, ALLOCATOR, "--self-test")
      assert status.success?, err
      assert_equal "allocator self-test passed\n", out
    end
    out, _, status = Open3.capture3(RUBY, "allocator.rb", "not-a-key", chdir: __dir__)
    refute status.success?
    assert_empty out
  end
end
