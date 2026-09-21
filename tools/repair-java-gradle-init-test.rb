require 'minitest/autorun'
require 'fileutils'
require_relative 'repair-java-gradle-init'

class JavaGradleInitRepairTest < Minitest::Test
  def setup
    @directory = Dir.mktmpdir('tapetum-init-test-')
    @source = "// Test fixture #{File.basename(@directory)}\nallprojects {}\n"
    @archive = File.join(@directory, 'org.eclipse.jdt.ls.core_test.jar')
    @entry = 'gradle/init/init.gradle'
    FileUtils.mkdir_p(File.join(@directory, 'gradle/init'))
    File.write(File.join(@directory, @entry), @source)
    _, status = Open3.capture2('zip', '-q', @archive, @entry, chdir: @directory)
    raise 'Cannot create test archive' unless status.success?
    @destination = File.join(Dir.tmpdir, "#{Digest::SHA256.hexdigest(@source)}.gradle")
  end

  def teardown
    File.unlink(@destination) if File.exist?(@destination) || File.symlink?(@destination)
    File.unlink(@extra_destination) if @extra_destination && File.exist?(@extra_destination)
    FileUtils.remove_entry(@directory)
  end

  def test_restores_exact_resource
    assert_equal @entry, JavaGradleInitRepair.restore(@archive, @destination)
    assert_equal @source, File.binread(@destination)
    assert_equal 0o600, File.stat(@destination).mode & 0o777
  end

  def test_rejects_nonmatching_hash
    destination = File.join(Dir.tmpdir, "#{Digest::SHA256.hexdigest(@source + 'wrong')}.gradle")
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore(@archive, destination) }
    refute File.exist?(destination)
  end

  def test_does_not_overwrite
    File.write(@destination, 'existing')
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore(@archive, @destination) }
    assert_equal 'existing', File.read(@destination)
  end

  def test_rejects_symlink
    target = File.join(@directory, 'untouched')
    File.symlink(target, @destination)
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore(@archive, @destination) }
    refute File.exist?(target)
  end

  def test_rejects_other_directories
    destination = File.join(@directory, File.basename(@destination))
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore(@archive, destination) }
    refute File.exist?(destination)
  end

  def test_rejects_non_hash_filename
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore(@archive, File.join(Dir.tmpdir, 'init.gradle')) }
  end

  def test_rejects_other_archives
    other = File.join(@directory, 'other.jar')
    FileUtils.cp(@archive, other)
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore(other, @destination) }
    refute File.exist?(@destination)
  end

  def add_second_script
    source = @source + '// Second bundled resource'
    entry = 'gradle/init/second.gradle'
    File.write(File.join(@directory, entry), source)
    _, status = Open3.capture2('zip', '-q', @archive, entry, chdir: @directory)
    raise 'Cannot update test archive' unless status.success?
    @extra_destination = File.join(Dir.tmpdir, "#{Digest::SHA256.hexdigest(source)}.gradle")
    entry
  end

  def test_batch_restores_all_and_is_idempotent
    extra = add_second_script
    result = JavaGradleInitRepair.restore_all(@archive)
    assert_equal({@entry => 'restored', extra => 'restored'}, result)
    assert_equal @source, File.binread(@destination)
    assert File.file?(@extra_destination)
    assert_equal({@entry => 'verified', extra => 'verified'}, JavaGradleInitRepair.restore_all(@archive))
  end

  def test_batch_preflight_rejects_conflict_before_writing
    add_second_script
    File.write(@extra_destination, 'unrelated')
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore_all(@archive) }
    refute File.exist?(@destination)
    assert_equal 'unrelated', File.read(@extra_destination)
  end

  def test_batch_rejects_symlink_even_to_matching_content
    File.symlink(File.join(@directory, @entry), @destination)
    assert_raises(ArgumentError) { JavaGradleInitRepair.restore_all(@archive) }
    assert File.symlink?(@destination)
  end
end
