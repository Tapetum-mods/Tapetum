#version 120

varying vec2 atlasUv;
varying vec2 lightUv;
varying vec4 vertexColor;

void main() {
    gl_Position = ftransform();
    atlasUv = gl_MultiTexCoord0.xy;
    lightUv = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vertexColor = gl_Color;
}
