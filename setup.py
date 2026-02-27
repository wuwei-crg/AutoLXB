"""
LXB-Link Setup Configuration

Install the package in development mode:
    pip install -e .

Install for production:
    pip install .
"""

from setuptools import setup, find_packages

# Read README for long description
try:
    with open("README.md", "r", encoding="utf-8") as fh:
        long_description = fh.read()
except FileNotFoundError:
    long_description = "LXB-Link: Reliable UDP Protocol for Android Device Control"

setup(
    name="lxb-link",
    version="0.0.1",
    author="Runguo Chen",
    author_email="1415327394@bupt.edu.cn",
    description="Reliable UDP Protocol for Android Device Control using Stop-and-Wait ARQ",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/wuwei-crg/LXB-Framework",
    package_dir={"": "src"},
    packages=find_packages(where="src"),
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Developers",
        "Topic :: Software Development :: Libraries :: Python Modules",
        "Topic :: System :: Networking",
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.8",
        "Programming Language :: Python :: 3.9",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Operating System :: OS Independent",
    ],
    python_requires=">=3.8",
    install_requires=[
        # No external dependencies - uses only Python standard library
    ],
    extras_require={
        "dev": [
            "pytest>=7.0.0",
            "pytest-cov>=4.0.0",
            "black>=23.0.0",
            "flake8>=6.0.0",
            "mypy>=1.0.0",
        ],
    },
    entry_points={
        "console_scripts": [
            # Add CLI commands here if needed
        ],
    },
    keywords=[
        "udp",
        "reliable",
        "protocol",
        "android",
        "device-control",
        "stop-and-wait",
        "arq",
        "networking",
    ],
    project_urls={
        "Bug Reports": "https://github.com/wuwei-crg/LXB-Framework/issues",
        "Source": "https://github.com/wuwei-crg/LXB-Framework",
        "Documentation": "https://github.com/wuwei-crg/LXB-Framework/blob/main/README.md",
    },
)
