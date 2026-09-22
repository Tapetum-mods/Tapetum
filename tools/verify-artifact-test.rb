require 'minitest/autorun'
require 'tmpdir'
require 'fileutils'
require_relative 'verify-artifact'

class TapetumArtifactTest < Minitest::Test
  def setup
    @directory = Dir.mktmpdir('tapetum-artifact-')
    @metadata = {'id' => 'tapetumshaders', 'license' => 'LGPL-3.0-only',
      'version' => '0.1.0-snapshot+mc26.1.2-local',
      'depends' => {'fabricloader' => '>=0.19.3', 'java' => '>=25', 'minecraft' => '26.1.2'}}
  end

  def teardown
    FileUtils.remove_entry(@directory)
  end

  def artifact(extra_entry = nil)
    entries = ['fabric.mod.json', 'dev/tapetum/shaders/TapetumShaders.class', 'META-INF/licenses/tapetum/LICENSE']
    entries << extra_entry if extra_entry
    entries.each do |entry|
      path = File.join(@directory, entry)
      FileUtils.mkdir_p(File.dirname(path))
      File.write(path, entry == 'fabric.mod.json' ? JSON.generate(@metadata) : 'test fixture')
    end
    path = File.join(@directory, "tapetum-shaders-#{@metadata['version']}.jar")
    _, status = Open3.capture2('zip', '-q', path, *entries, chdir: @directory)
    raise 'Cannot create fixture' unless status.success?
    path
  end

  def test_local_snapshot
    result = TapetumArtifact.verify(artifact)
    assert_equal '26.1.2', result[:game]
    assert_match(/\A[0-9a-f]{64}\z/, result[:sha256])
  end

  def test_ci_snapshot
    @metadata['version'] = '0.1.0-snapshot+mc26.1.2-build.42'
    assert_equal @metadata['version'], TapetumArtifact.verify(artifact)[:version]
  end

  def test_release
    @metadata['version'] = '0.1.0+mc26.1.2'
    assert_equal @metadata['version'], TapetumArtifact.verify(artifact, release_tag: 'v0.1.0+mc26.1.2')[:version]
  end

  def test_snapshot_is_not_a_release
    assert_raises(ArgumentError) { TapetumArtifact.verify(artifact, release_tag: "v#{@metadata['version']}") }
  end

  def test_wrong_release_tag
    @metadata['version'] = '0.1.0+mc26.1.2'
    assert_raises(ArgumentError) { TapetumArtifact.verify(artifact, release_tag: 'v0.1.0+mc26.2') }
  end

  def test_rejects_version_range
    @metadata['depends']['minecraft'] = '~26.1.2'
    assert_raises(ArgumentError) { TapetumArtifact.verify(artifact) }
  end

  def test_rejects_wrong_game
    @metadata['depends']['minecraft'] = '26.2'
    assert_raises(ArgumentError) { TapetumArtifact.verify(artifact) }
  end

  def test_rejects_renderer_dependency
    @metadata['depends']['sodium'] = '*'
    assert_raises(ArgumentError) { TapetumArtifact.verify(artifact) }
  end

  def test_rejects_bundled_renderer
    assert_raises(ArgumentError) { TapetumArtifact.verify(artifact('META-INF/jars/iris.jar')) }
  end

  def test_rejects_misnamed_jar
    path = artifact
    other = File.join(@directory, 'renamed.jar')
    FileUtils.mv(path, other)
    assert_raises(ArgumentError) { TapetumArtifact.verify(other) }
  end
end
